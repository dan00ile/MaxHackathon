package mkd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
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
        // раньше ветки pending: сброс должен работать и посреди незаконченного диалога
        if (text == "/reset") {
            handleResetPrompt(userId)
            return
        }
        when (val p = pending[userId]) {
            is Pending.ChairFio -> handleChairFioText(userId, p.houseId, text)
            is Pending.ChairBasis -> handleChairBasisText(userId, p.houseId, p.fio, text)
            is Pending.ReceiptDate -> handleReceiptDateText(userId, p.actId, text)
            null -> when {
                text == "/start" || text == "/menu" -> entryPoint(userId)
                text == "/status" -> entryPoint(userId)
                text.startsWith("/shift") -> handleShift(userId, text)
                else -> Unit
            }
        }
    }

    private fun firstActAttachment(attachments: List<JsonObject>): Pair<String, String>? {
        val att = attachments.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull in listOf("file", "image") }
            ?: return null
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
        val act = try {
            acts.confirmReceipt(actId, userId, date)
        } catch (e: ApiError) {
            // дата вне окна — ждём следующую, как и при ошибке формата; иначе (дата уже зафиксирована,
            // акт недоступен) ждать нечего
            if (e.code != "invalid_date") pending.remove(userId)
            max.sendText(userId, e.message)
            return
        }
        pending.remove(userId)
        finalizeReceipt(userId, act)
    }

    // возвращает, чем заменить сообщение с вопросом о дате: кнопки «Сегодня/Другая дата» после ответа не нужны
    private suspend fun handleRcvToday(userId: Long, actId: Long): SendMessageRequest {
        val act = try {
            acts.confirmReceipt(actId, userId, null)
        } catch (e: ApiError) {
            return max.messageBody(e.message)
        }
        // «Сегодня» после «Другой даты»: ввод даты больше не ждём
        pending.remove(userId, Pending.ReceiptDate(actId))
        finalizeReceipt(userId, act)
        val date =receiptDateFormat.withZone(cfg.zone).format(act[Acts.receivedAt])
        return max.messageBody("Дата получения акта зафиксирована — $date. От неё идут сроки 10 и 30 дней.")
    }

    private suspend fun finalizeReceipt(userId: Long, act: ResultRow) {
        val actId = act[Acts.id].value
        tx { logEvent(actId, "NOTIFY_D0", null) }
        sendStatus(userId, act[Acts.houseId].value, act)
        scope.launch { runCatching { acts.recognize(actId) }.onFailure { log.error("recognize", it) } }
    }

    private suspend fun onCallback(u: Update) {
        val callback = u.callback ?: return
        val userId = callback.user.userId
        val (cmd, arg) = (callback.payload ?: "").split(":", limit = 2).let { it[0] to it.getOrNull(1) }
        var edit: SendMessageRequest? = null
        runCatching {
            when (cmd) {
                "consent" -> handleConsent(userId, callback.user.displayName())
                "house" -> handleHouseChosen(userId, arg!!.toLong())
                "role_res" -> handleRoleResident(userId, arg!!.toLong())
                "role_chair" -> handleRoleChairman(userId, arg!!.toLong())
                "approve" -> handleApprove(userId, arg!!.toLong())
                "rcv_today" -> edit = handleRcvToday(userId, arg!!.toLong())
                "rcv_other" -> edit = handleRcvOther(userId, arg!!.toLong())
                // меню и статусы акта сменяют друг друга в одном сообщении: status — меню, status:<id> — акт
                "status" -> edit = if (arg == null) menuEdit(userId) ?: run { entryPoint(userId); null }
                else statusEdit(userId, arg.toLong()) ?: run { sendActStatus(userId, arg.toLong()); null }

                "close" -> handleClose(userId, arg!!.toLong())
                "sign" -> handleSignPrompt(userId, arg!!.toLong())
                "sign_ok" -> handleSignOk(userId, arg!!.toLong())
                "refuse" -> handleRefuse(userId, arg!!.toLong())
                "sent" -> handleSent(userId, arg!!.toLong())
                "reset_ok" -> handleResetOk(userId)
            }
        }.onFailure { log.error("callback {}", callback.payload, it) }
        val answered = runCatching { max.answerCallback(callback.callbackId, if (edit == null) "ок" else null, edit) }
            .onFailure { log.warn("answerCallback failed: {}", it.message) }
            .getOrDefault(false)
        // MAX не заменил сообщение (слишком старое, удалено) — статус не теряем, присылаем новым
        if (edit != null && !answered) {
            when {
                cmd != "status" -> max.sendMessage(userId, edit!!)
                arg == null -> entryPoint(userId)
                else -> sendActStatus(userId, arg.toLong())
            }
        }
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
        entryPoint(userId) // уже привязан к дому — меню, а не повторный выбор дома
    }

    private suspend fun sendHouseChoice(userId: Long) {
        val houses = tx { Houses.selectAll().orderBy(Houses.id).map { it[Houses.id].value to it[Houses.address] } }
        max.sendText(userId, "Выберите ваш дом:", houses.map { (id, addr) -> listOf(cb("$addr (демо)", "house:$id")) })
    }

    private suspend fun handleHouseChosen(userId: Long, houseId: Long) {
        if (tx { userRowTx(userId) }?.second != null) return rejectRebind(userId)
        max.sendText(
            userId, "Кто вы?",
            listOf(
                listOf(cb("Житель", "role_res:$houseId")),
                listOf(cb("Председатель совета дома", "role_chair:$houseId"))
            ),
        )
    }

    private suspend fun handleRoleResident(userId: Long, houseId: Long) {
        if (!tx { bindHouseTx(userId, houseId) }) return rejectRebind(userId)
        sendMenu(userId, houseId)
    }

    private suspend fun handleRoleChairman(userId: Long, houseId: Long) {
        if (!tx { bindHouseTx(userId, houseId) }) return rejectRebind(userId)
        pending[userId] = Pending.ChairFio(houseId)
        max.sendText(userId, "Напишите ваши ФИО полностью — они будут в документах.")
    }

    private suspend fun rejectRebind(userId: Long) {
        max.sendText(userId, "Дом и статус уже выбраны, сменить их нельзя. Продолжайте работу с актами.")
        entryPoint(userId)
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
        // акт в работе, а если его нет — последний акт с отказом (проверить напоминание о новом акте)
        val actId = tx {
            activeActTx(houseId) ?: houseActsTx(houseId).firstOrNull { it[Acts.status] == ActStatus.REJECTED }
        }?.get(Acts.id)?.value
        if (actId == null) {
            max.sendText(userId, "Нет акта в работе или с направленным отказом.")
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

    private suspend fun sendActStatus(userId: Long, actId: Long) {
        val act = try {
            acts.requireMemberOf(actId, userId)
        } catch (e: ApiError) {
            max.sendText(userId, e.message)
            return
        }
        sendStatus(userId, act[Acts.houseId].value, act)
    }

    private suspend fun houseAddress(houseId: Long): String =
        tx { Houses.select(Houses.address).where { Houses.id eq houseId }.single()[Houses.address] }

    private suspend fun sendStatus(userId: Long, houseId: Long, act: ResultRow) {
        max.sendMessage(userId, statusMessage(userId, houseId, act))
    }

    // updatedAt != null — статус заменяет собой прежнее сообщение, поэтому помечаем время
    private suspend fun actStatusText(houseId: Long, act: ResultRow, updatedAt: Instant? = null): String {
        val eventAt = acts.terminalEventAt(act[Acts.id].value, act[Acts.status])
        return statusText(act, houseAddress(houseId), Instant.now(), cfg.zone, eventAt) +
                (updatedAt?.let { updatedNote(it, cfg.zone) } ?: "")
    }

    private suspend fun statusMessage(
        userId: Long, houseId: Long, act: ResultRow, updatedAt: Instant? = null,
    ): SendMessageRequest = max.messageBody(
        actStatusText(houseId, act, updatedAt),
        statusButtons(act, rolesOf(userId).chairman),
    )

    // null — акт недоступен (архив, чужой дом): sendActStatus ответит обычным сообщением с причиной
    private suspend fun statusEdit(userId: Long, actId: Long): SendMessageRequest? {
        val act = runCatching { acts.requireMemberOf(actId, userId) }.getOrNull() ?: return null
        return statusMessage(userId, act[Acts.houseId].value, act, updatedAt = Instant.now())
    }

    // кнопки не убираем: пока дата не введена, «Сегодня» ещё может понадобиться
    private suspend fun handleRcvOther(userId: Long, actId: Long): SendMessageRequest? {
        try {
            acts.requireReceiptOpen(actId, userId)
        } catch (e: ApiError) {
            return max.messageBody(e.message)
        }
        pending[userId] = Pending.ReceiptDate(actId)
        max.sendText(userId, "Напишите дату в формате ДД.ММ.ГГГГ")
        return null
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
        val residents =
            tx { Users.selectAll().where { (Users.houseId eq houseId) and (Users.id neq userId) }.map { it[Users.id] } }
        residents.forEach { uid ->
            runCatching {
                max.sendText(
                    uid,
                    "Сбор замечаний по акту № $actNumber завершён. Спасибо!"
                )
            }
        }
    }

    private suspend fun handleSignPrompt(userId: Long, actId: Long) {
        val act = try {
            acts.requireChairmanOf(actId, userId)
        } catch (e: ApiError) {
            max.sendText(userId, e.message); return
        }
        if (act[Acts.status] != ActStatus.COLLECTING && act[Acts.status] != ActStatus.REVIEW) {
            max.sendText(userId, "Акт уже подписан, направлен отказ или принят молчаливым согласием")
            return
        }
        val disputed = tx {
            ActItems.selectAll().where { (ActItems.actId eq actId) and (ActItems.decision eq Decision.DISPUTE) }.count()
        }
        var text = "Подписать акт без возражений? Оспариваемых позиций: $disputed."
        if (disputed > 0) text += "\nЗамечания жителей в документ не попадут."
        max.sendText(
            userId,
            text,
            listOf(
                listOf(cb("Да, подписать", "sign_ok:$actId")),
                listOf(cb("Нет", "status:$actId"))
            )
        )
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

    private suspend fun handleResetPrompt(userId: Long) {
        if (!cfg.demoMode) {
            max.sendText(userId, "Команда доступна только в демо-режиме.")
            return
        }
        max.sendText(
            userId,
            "Выйти из дома и начать заново? Активный акт уйдёт в архив: сам акт, замечания жителей, " +
                    "фото и уже сформированные документы сохранятся, но перестанут показываться в боте.",
            listOf(listOf(cb("Да, начать заново", "reset_ok")), listOf(cb("Отмена", "status"))),
        )
    }

    private suspend fun handleResetOk(userId: Long) {
        if (!cfg.demoMode) return
        pending.remove(userId)
        val result = resetUser(userId)
        val archived =
            if (result.archivedActIds.isEmpty()) ""
            else " Актов отправлено в архив: ${result.archivedActIds.size}, данные и документы сохранены."
        max.sendText(userId, "Готово, вы вышли из дома.$archived")
        entryPoint(userId)
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
        max.sendMessage(userId, menuMessage(userId, houseId))
    }

    private suspend fun menuMessage(userId: Long, houseId: Long): SendMessageRequest {
        val houseActs = tx { houseActsTx(houseId) }
        return max.messageBody(menuText(houseActs, rolesOf(userId).chairman), menuButtons(houseActs))
    }

    // «‹ Все акты» из статуса акта: меню заменяет это же сообщение; null — дома нет, отвечаем как /start
    private suspend fun menuEdit(userId: Long): SendMessageRequest? {
        val (consented, houseId) = tx { userRowTx(userId) } ?: return null
        return if (consented && houseId != null) menuMessage(userId, houseId) else null
    }
}

// Привязка к дому одна на пользователя: старые сообщения с выбором дома и роли остаются кликабельными,
// поэтому пишем только в пустую колонку — одним update, чтобы два быстрых нажатия не обошли проверку
fun bindHouseTx(userId: Long, houseId: Long): Boolean =
    Users.update({ (Users.id eq userId) and Users.houseId.isNull() }) {
        it[Users.houseId] = EntityID(houseId, Houses)
    } > 0
