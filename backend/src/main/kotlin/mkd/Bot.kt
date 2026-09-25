package mkd

import kotlinx.coroutines.delay
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant
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

class Bot(private val cfg: Config, private val max: MaxBotClient) {
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
        val text = message.body.text?.trim() ?: return   // вложения акта — S9
        when (val p = pending[userId]) {
            is Pending.ChairFio -> handleChairFioText(userId, p.houseId, text)
            is Pending.ChairBasis -> handleChairBasisText(userId, p.houseId, p.fio, text)
            is Pending.ReceiptDate -> Unit // S9
            null -> when (text) {
                "/start", "/menu" -> entryPoint(userId)
                else -> Unit
            }
        }
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
