package mkd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

sealed interface Pending {
    data class ChairFio(val houseId: Long) : Pending
    data class ChairBasis(val houseId: Long, val fio: String) : Pending
    data class ReceiptDate(val actId: Long) : Pending     // S9
}

fun appLink(startParam: String) = "https://max.ru/$botUsername?startapp=$startParam"

private const val CONSENT_TEXT = "Бот помогает совету дома принять или обоснованно отклонить акт работ УК. " +
    "Мы храним ваше имя в MAX, привязку к дому и ваши отметки по акту. " +
    "Нажимая кнопку, вы соглашаетесь на обработку этих данных."

private val receiptDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

class Bot(
    private val cfg: Config, private val max: MaxBotClient, private val acts: ActService,
    private val refusal: RefusalService, private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(Bot::class.java)
    private val pending = ConcurrentHashMap<Long, Pending>()

    suspend fun pollLoop() {
        var marker: Long? = null
        while (true) {
            try {
                val list = max.getUpdates(marker)
                list.updates.forEach { update ->
                    runCatching { handle(update) }.onFailure { log.error("update", it) }
                }
                marker = list.marker ?: marker
            } catch (e: Exception) {
                log.warn("poll failed: {}", e.message)
                delay(3000)
            }
        }
    }

    suspend fun handle(u: Update) {
        when (u.type) {
            "bot_started" -> u.user?.let { entryPoint(it.userId) }
            "message_callback" -> onCallback(u)
            "message_created" -> onMessage(u)
        }
    }

    private suspend fun onMessage(u: Update) {
        val message = u.message ?: return
        val userId = message.sender?.userId ?: return
        val attachment = firstActAttachment(message.body.attachments)
        if (attachment != null) {
            handleActUpload(userId, message.timestamp, message.body.mid, attachment.first, attachment.second)
            return
        }
        val text = message.body.text?.trim() ?: return
        when (val p = pending[userId]) {
            is Pending.ChairFio -> handleChairFioText(userId, p.houseId, text)
            is Pending.ChairBasis -> handleChairBasisText(userId, p.houseId, p.fio, text)
            is Pending.ReceiptDate -> handleReceiptDateText(userId, p.actId, text)
            null -> when {
                text == "/start" || text == "/menu" -> entryPoint(userId)
                text == "/status" -> sendActiveStatus(userId)
                text.startsWith("/shift") -> handleShift(userId, text)
                else -> Unit
            }
        }
    }

    private fun firstActAttachment(attachments: List<JsonObject>): Pair<String, String>? {
        val att = attachments.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull in listOf("file", "image") } ?: return null
        val type = att["type"]!!.jsonPrimitive.content
        val url = att["payload"]!!.jsonObject["url"]!!.jsonPrimitive.content
        val fileName = att["filename"]?.jsonPrimitive?.contentOrNull ?: if (type == "image") "photo.jpg" else "act.pdf"
        return url to fileName
    }

    private fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        else -> "image/jpeg"
    }

    private suspend fun handleActUpload(userId: Long, timestampMs: Long, mid: String, url: String, fileName: String) {
        val roles = rolesOf(userId)
        if (!roles.chairman) {
            max.sendText(userId, "Загружать акт может только председатель совета дома.")
            return
        }
        val houseId = roles.houseId!!
        val active = acts.activeAct(houseId)
        if (active != null) {
            max.sendText(userId, "Сначала завершите текущий акт № ${active[Acts.id].value} (подпишите или направьте отказ).")
            return
        }
        val bytes = max.download(url)
        val receivedAt = Instant.ofEpochMilli(timestampMs)
        val actId = acts.createFromUpload(userId, houseId, bytes, fileName, mimeOf(fileName), receivedAt, mid)
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(cfg.zone)
        max.sendText(
            userId, "Акт получен ${fmt.format(receivedAt)}. Когда вы получили этот акт от УК?",
            listOf(listOf(cb("Сегодня", "rcv_today:$actId")), listOf(cb("Другая дата", "rcv_other:$actId"))),
        )
    }

    private suspend fun handleReceiptDateText(userId: Long, actId: Long, text: String) {
        val date = runCatching { LocalDate.parse(text.trim(), receiptDateFormat) }.getOrNull()
        if (date == null) {
            max.sendText(userId, "Не получилось разобрать дату. Напишите в формате ДД.ММ.ГГГГ, например 20.09.2026")
            return
        }
        pending.remove(userId)
        try {
            acts.setReceiptDate(actId, userId, date)
        } catch (e: ApiError) {
            max.sendText(userId, e.message)
            return
        }
        finalizeReceipt(userId, actId)
    }

    private suspend fun finalizeReceipt(userId: Long, actId: Long) {
        val act = tx { Acts.selectAll().where { Acts.id eq actId }.single() }
        tx { logEvent(actId, "NOTIFY_D0", null) }
        sendStatus(userId, act[Acts.houseId].value, act)
        scope.launch { runCatching { acts.recognize(actId) }.onFailure { log.error("recognize", it) } }
    }

    private suspend fun onCallback(u: Update) {
        val callback = u.callback ?: return
        val userId = callback.user.userId
        val (cmd, arg) = (callback.payload ?: "").split(":", limit = 2).let { it[0] to it.getOrNull(1) }
        runCatching {
            when (cmd) {
                "consent" -> handleConsent(userId, callback.user.displayName())
                "house" -> handleHouseChosen(userId, arg!!.toLong())
                "role_res" -> handleRoleResident(userId, arg!!.toLong())
                "role_chair" -> handleRoleChairman(userId, arg!!.toLong())
                "approve" -> handleApprove(userId, arg!!.toLong())
                "rcv_today" -> finalizeReceipt(userId, arg!!.toLong())
                "rcv_other" -> handleRcvOther(userId, arg!!.toLong())
                "status" -> sendActiveStatus(userId)
                "close" -> handleClose(userId, arg!!.toLong())
                "sign" -> handleSignPrompt(userId, arg!!.toLong())
                "sign_ok" -> handleSignOk(userId, arg!!.toLong())
                "refuse" -> handleRefuse(userId, arg!!.toLong())
                "sent" -> handleSent(userId, arg!!.toLong())
            }
        }.onFailure { log.error("callback {}", callback.payload, it) }
        runCatching { max.answerCallback(callback.callbackId, "ок") }
            .onFailure { log.warn("answerCallback failed: {}", it.message) }
    }

    private fun userRowTx(userId: Long): Pair<Boolean, Long?>? =
        Users.selectAll().where { Users.id eq userId }.singleOrNull()
            ?.let { (it[Users.pdConsentAt] != null) to it[Users.houseId]?.value }

    private suspend fun entryPoint(userId: Long) {
        val (consented, houseId) = tx { userRowTx(userId) } ?: (false to null)
        if (!consented) {
            max.sendText(userId, CONSENT_TEXT, listOf(listOf(cb("Согласен", "consent"))))
        } else {
            sendMenu(userId, houseId)
        }
    }

    private suspend fun handleConsent(userId: Long, displayName: String) {
        tx {
            val exists = Users.selectAll().where { Users.id eq userId }.count() > 0
            if (exists) {
                Users.update({ Users.id eq userId }) { it[pdConsentAt] = Instant.now() }
            } else {
                Users.insert {
                    it[id] = userId
                    it[name] = displayName
                    it[pdConsentAt] = Instant.now()
                    it[createdAt] = Instant.now()
                }
            }
            logEvent(null, "USER_REGISTERED", userId)
        }
        sendHouseChoice(userId)
    }

    private suspend fun sendHouseChoice(userId: Long) {
        val houses = tx { Houses.selectAll().orderBy(Houses.id).map { it[Houses.id].value to it[Houses.address] } }
        max.sendText(userId, "Выберите ваш дом:", houses.map { (id, addr) -> listOf(cb("$addr (демо)", "house:$id")) })
    }

    private suspend fun handleHouseChosen(userId: Long, houseId: Long) {
        max.sendText(
            userId, "Кто вы?",
            listOf(listOf(cb("Житель", "role_res:$houseId")), listOf(cb("Председатель совета дома", "role_chair:$houseId"))),
        )
    }

    private suspend fun handleRoleResident(userId: Long, houseId: Long) {
        tx { Users.update({ Users.id eq userId }) { it[Users.houseId] = EntityID(houseId, Houses) } }
        sendMenu(userId, houseId)
    }

    private suspend fun handleRoleChairman(userId: Long, houseId: Long) {
        tx { Users.update({ Users.id eq userId }) { it[Users.houseId] = EntityID(houseId, Houses) } }
        pending[userId] = Pending.ChairFio(houseId)
        max.sendText(userId, "Напишите ваши ФИО полностью — они будут в документах.")
    }

    private suspend fun handleChairFioText(userId: Long, houseId: Long, text: String) {
        pending[userId] = Pending.ChairBasis(houseId, text)
        max.sendText(userId, "Чем подтверждены ваши полномочия? Например: протокол общего собрания № 5 от 01.03.2026")
    }

    private suspend fun handleChairBasisText(userId: Long, houseId: Long, fio: String, basis: String) {
        pending.remove(userId)
        val autoConfirm = cfg.adminUserIds.isEmpty()
        val (rowId, address) = tx {
            val id = Chairmen.insert {
                it[Chairmen.userId] = userId
                it[Chairmen.houseId] = EntityID(houseId, Houses)
                it[fullName] = fio
                it[authorityBasis] = basis
                it[confirmedAt] = if (autoConfirm) Instant.now() else null
            } get Chairmen.id
            logEvent(null, "CHAIRMAN_REGISTERED", userId, "houseId=$houseId")
            id.value to Houses.select(Houses.address).where { Houses.id eq houseId }.single()[Houses.address]
        }
        if (autoConfirm) {
            max.sendText(userId, "Готово, вы председатель дома $address.")
            sendMenu(userId, houseId)
        } else {
            max.sendText(userId, "Заявка отправлена оператору.")
            cfg.adminUserIds.forEach { adminId ->
                max.sendText(
                    adminId, "Заявка председателя: $fio, $address, основание: $basis",
                    listOf(listOf(cb("Подтвердить", "approve:$rowId"))),
                )
            }
        }
    }

    private suspend fun handleShift(userId: Long, text: String) {
        val days = text.removePrefix("/shift").trim().toIntOrNull()
        val houseId = rolesOf(userId).houseId
        if (days == null || houseId == null) {
            max.sendText(userId, "Использование: /shift N (число дней, только в демо-режиме)")
            return
        }
        val actId = acts.activeAct(houseId)?.get(Acts.id)?.value
        if (actId == null) {
            max.sendText(userId, "Нет активного акта.")
            return
        }
        try {
            acts.demoShift(actId, userId, days)
        } catch (e: ApiError) {
            max.sendText(userId, e.message)
            return
        }
        val act = tx { Acts.selectAll().where { Acts.id eq actId }.single() }
        sendStatus(userId, houseId, act)
    }

    private suspend fun sendActiveStatus(userId: Long) {
        val houseId = rolesOf(userId).houseId
        if (houseId == null) {
            max.sendText(userId, "Сначала выберите дом.")
            return
        }
        val act = acts.activeAct(houseId) ?: acts.lastAct(houseId)
        if (act == null) {
            max.sendText(userId, "Активного акта нет.")
            return
        }
        sendStatus(userId, houseId, act)
    }

    private suspend fun sendStatus(userId: Long, houseId: Long, act: ResultRow) {
        val address = tx { Houses.select(Houses.address).where { Houses.id eq houseId }.single()[Houses.address] }
        val roles = rolesOf(userId)
        val eventAt = acts.terminalEventAt(act[Acts.id].value, act[Acts.status])
        val text = statusText(act, address, Instant.now(), cfg.zone, eventAt)
        max.sendText(userId, text, statusButtons(cfg, act, roles.chairman, roles.resident))
    }

    private suspend fun handleRcvOther(userId: Long, actId: Long) {
        pending[userId] = Pending.ReceiptDate(actId)
        max.sendText(userId, "Напишите дату в формате ДД.ММ.ГГГГ")
    }

    private suspend fun handleClose(userId: Long, actId: Long) {
        try {
            acts.closeCollection(actId, userId)
        } catch (e: ApiError) {
            max.sendText(userId, e.message)
            return
        }
        val act = tx { Acts.selectAll().where { Acts.id eq actId }.single() }
        val houseId = act[Acts.houseId].value
        sendStatus(userId, houseId, act)
        val actNumber = act[Acts.number] ?: "без номера"
        val residents = tx { Users.selectAll().where { (Users.houseId eq houseId) and (Users.id neq userId) }.map { it[Users.id] } }
        residents.forEach { uid -> runCatching { max.sendText(uid, "Сбор замечаний по акту № $actNumber завершён. Спасибо!") } }
    }

    private suspend fun handleSignPrompt(userId: Long, actId: Long) {
        val act = try { acts.requireChairmanOf(actId, userId) } catch (e: ApiError) { max.sendText(userId, e.message); return }
        if (act[Acts.status] != ActStatus.COLLECTING && act[Acts.status] != ActStatus.REVIEW) {
            max.sendText(userId, "Акт уже подписан, направлен отказ или принят молчаливым согласием")
            return
        }
        val disputed = tx { ActItems.selectAll().where { (ActItems.actId eq actId) and (ActItems.decision eq Decision.DISPUTE) }.count() }
        var text = "Подписать акт без возражений? Оспариваемых позиций: $disputed."
        if (disputed > 0) text += "\nЗамечания жителей в документ не попадут."
        max.sendText(userId, text, listOf(listOf(cb("Да, подписать", "sign_ok:$actId")), listOf(cb("Нет", "status"))))
    }

    private suspend fun handleSignOk(userId: Long, actId: Long) {
        try {
            acts.sign(actId, userId)
        } catch (e: ApiError) {
            max.sendText(userId, e.message)
        }
    }

    private suspend fun handleRefuse(userId: Long, actId: Long) {
        val dto = try {
            refusal.draft(actId, userId, rebuild = false)
        } catch (e: ApiError) {
            max.sendText(userId, e.message)
            return
        }
        max.sendText(
            userId, "Черновик отказа готов: возражений — ${dto.objections.size}. Проверьте формулировки и подтвердите.",
            listOf(listOf(link("Открыть черновик", appLink("refusal_$actId")))),
        )
    }

    private suspend fun handleSent(userId: Long, actId: Long) {
        try {
            refusal.markSent(actId, userId)
        } catch (e: ApiError) {
            max.sendText(userId, e.message)
        }
    }

    private suspend fun handleApprove(adminUserId: Long, chairmanRowId: Long) {
        if (adminUserId !in cfg.adminUserIds) return
        val confirmed = tx {
            val row = Chairmen.selectAll().where { Chairmen.id eq chairmanRowId }.singleOrNull()
            if (row == null) {
                null
            } else {
                Chairmen.update({ Chairmen.id eq chairmanRowId }) { it[confirmedAt] = Instant.now() }
                logEvent(null, "CHAIRMAN_CONFIRMED", adminUserId, "chairmanId=$chairmanRowId")
                row[Chairmen.userId] to row[Chairmen.houseId].value
            }
        } ?: return
        val (chairUserId, houseId) = confirmed
        max.sendText(chairUserId, "Ваша заявка председателя подтверждена.")
        sendMenu(chairUserId, houseId)
    }

    private suspend fun sendMenu(userId: Long, houseId: Long?) {
        if (houseId == null) {
            sendHouseChoice(userId)
            return
        }
        val roles = rolesOf(userId)
        val activeActId = activeActIdOf(houseId)
        if (roles.chairman) {
            val buttons = mutableListOf(listOf(cb("Статус акта", "status")))
            if (activeActId != null) buttons.add(listOf(link("Открыть акт", appLink("act_$activeActId"))))
            max.sendText(userId, "Чтобы начать, пришлите сюда файл акта (PDF или фото).", buttons)
        } else {
            val buttons = mutableListOf(listOf(cb("Статус акта", "status")))
            val collecting = activeActId != null &&
                tx { Acts.select(Acts.status).where { Acts.id eq activeActId }.single()[Acts.status] } == ActStatus.COLLECTING
            if (collecting) buttons.add(listOf(link("Отметить работы", appLink("act_$activeActId"))))
            max.sendText(userId, "Отслеживайте здесь проверку акта работ по вашему дому.", buttons)
        }
    }
}
