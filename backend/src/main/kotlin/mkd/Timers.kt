package mkd

import kotlinx.coroutines.delay
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class NotifyPlan(val skip: List<String>, val send: String?)

// sent — множество уже записанных NOTIFY_* событий акта
fun planNotifications(receivedAt: Instant, sent: Set<String>, now: Instant, zone: ZoneId): NotifyPlan {
    val due = Deadlines.MILESTONES
        .filter { now >= Deadlines.milestoneAt(receivedAt, it, zone) }
        .map { "NOTIFY_D$it" }
        .filter { it !in sent }
    if (due.isEmpty()) return NotifyPlan(emptyList(), null)
    return NotifyPlan(due.dropLast(1), due.last())
}

// П. 6 Порядка не задаёт срок, в который УК оформляет новый акт после отказа, поэтому «просрочки» нет —
// только одно мягкое напоминание председателю через месяц (обычный цикл актов по договору)
const val NEW_ACT_REMINDER_DAYS = 30

// акты с отказом, по которым пора напомнить о новом акте: со дня отправки отказа прошло NEW_ACT_REMINDER_DAYS,
// напоминания ещё не было и после отказа по дому не загружали ни одного акта; вызывать только внутри tx { }
fun dueNewActRemindersTx(now: Instant, zone: ZoneId): List<ResultRow> =
    (Acts innerJoin Refusals).selectAll()
        .where { (Acts.status eq ActStatus.REJECTED) and Acts.archivedAt.isNull() and Refusals.sentAt.isNotNull() }
        .filter { row ->
            val sentAt = row[Refusals.sentAt]!!
            now >= Deadlines.milestoneAt(sentAt, NEW_ACT_REMINDER_DAYS, zone) &&
                    Events.selectAll()
                        .where { (Events.actId eq row[Acts.id].value) and (Events.type eq "NOTIFY_NEW_ACT") }
                        .empty() &&
                    Acts.selectAll()
                        .where {
                            (Acts.houseId eq row[Acts.houseId]) and (Acts.id neq row[Acts.id]) and
                                    (Acts.createdAt greater sentAt)
                        }
                        .empty()
        }

class TimerService(
    private val cfg: Config,
    private val max: MaxBotClient,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(TimerService::class.java)

    suspend fun loop() {
        while (true) {
            runCatching { tick() }.onFailure { log.error("tick", it) }
            delay(60_000)
        }
    }

    suspend fun tick(now: Instant = clock.instant()) {
        val acts = tx {
            Acts.selectAll()
                .where { (Acts.status inList ACTIVE_STATUSES) and Acts.archivedAt.isNull() }
                .toList()
        }
        for (act in acts) {
            val actId = act[Acts.id].value
            if (now >= Deadlines.silentAt(act[Acts.deadline30], cfg.zone)) {
                tx {
                    val sent = Events.selectAll().where { Events.actId eq actId }
                        .map { it[Events.type] }.filter { it.startsWith("NOTIFY_") }.toSet()
                    Deadlines.MILESTONES.map { "NOTIFY_D$it" }.filter { it !in sent }
                        .forEach { type -> logEvent(actId, type, null, "skipped") }
                    Acts.update({ Acts.id eq actId }) { it[status] = ActStatus.SILENT }
                    logEvent(actId, "SILENT_CONSENT", null)
                }
                continue
            }
            val sent = tx {
                Events.selectAll().where { Events.actId eq actId }
                    .map { it[Events.type] }
                    .filter { it.startsWith("NOTIFY_") }
                    .toSet()
            }
            val plan = planNotifications(act[Acts.receivedAt], sent, now, cfg.zone)
            plan.skip.forEach { type -> tx { logEvent(actId, type, null, "skipped") } }
            val type = plan.send ?: continue
            val text = notifyText(type, act, now)
            if (text == null) {
                // NOTIFY_D0 уже отправлен ботом при подтверждении даты получения (S9) — таймер только фиксирует событие
                tx { logEvent(actId, type, null, "sent") }
                continue
            }
            if (notify(act[Acts.houseId].value, actId, text)) {
                tx { logEvent(actId, type, null, "sent") }
            }
        }

        val silentActs =
            tx { Acts.selectAll().where { (Acts.status eq ActStatus.SILENT) and Acts.archivedAt.isNull() }.toList() }
        for (act in silentActs) {
            val actId = act[Acts.id].value
            val alreadyNotified = tx {
                Events.selectAll().where { Events.actId eq actId }.map { it[Events.type] }.contains("NOTIFY_SILENT")
            }
            if (alreadyNotified) continue
            val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            val text =
                "Срок 30 дней истёк ${fmt.format(act[Acts.deadline30])}. Акт № ${act[Acts.number] ?: "без номера"} за " +
                        "${act[Acts.period] ?: "—"} считается подписанным (молчаливое согласие, п. 5 Порядка, приказ Минстроя № 318/пр)."
            if (notify(act[Acts.houseId].value, actId, text, includeResidents = true)) {
                tx { logEvent(actId, "NOTIFY_SILENT", null, "sent") }
            }
        }
        remindNewActs(now)
    }

    private suspend fun remindNewActs(now: Instant) {
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(cfg.zone)
        for (act in tx { dueNewActRemindersTx(now, cfg.zone) }) {
            val actId = act[Acts.id].value
            val text = "Отказ по акту № ${act[Acts.number] ?: "без номера"} за ${act[Acts.period] ?: "—"} " +
                    "направлен в УК ${fmt.format(act[Refusals.sentAt])}, но новый акт пока не загружен. " +
                    "Если УК его ещё не прислала — напомните ей оформить новый акт (п. 6 Порядка, приказ Минстроя " +
                    "№ 318/пр). Когда он придёт, загрузите его сюда файлом."
            if (notify(act[Acts.houseId].value, actId, text)) {
                tx { logEvent(actId, "NOTIFY_NEW_ACT", null, "sent") }
            }
        }
    }

    private fun notifyText(type: String, act: ResultRow, now: Instant): String? {
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val d10 = fmt.format(act[Acts.deadline10])
        val d30 = fmt.format(act[Acts.deadline30])
        val n10 = Deadlines.daysLeft(act[Acts.deadline10], now, cfg.zone)
        val n30 = Deadlines.daysLeft(act[Acts.deadline30], now, cfg.zone)
        // тексты считают остаток от «сейчас»: уведомление может уйти позже своего дня (простой сервера, /shift)
        return when (type) {
            "NOTIFY_D7" -> "Срок по приказу на подписание или отказ — до $d10, осталось дней: $n10. " +
                    "Посмотрите замечания жителей и примите решение."

            "NOTIFY_D10" -> (if (n10 >= 0) "Сегодня, $d10, последний день срока по приказу." else "Срок по приказу истёк $d10.") +
                    " Подписать или отказать ещё можно до $d30; после этого акт будет считаться подписанным без ваших возражений."

            "NOTIFY_D25" -> "Осталось дней: $n30. После $d30 акт будет считаться подписанным без ваших возражений."
            "NOTIFY_D28" -> "Внимание: осталось дней: $n30. После $d30 акт будет считаться подписанным."
            "NOTIFY_D29" -> "Внимание: последний день для решения — $d30 (осталось дней: $n30). Если ничего не сделать, акт будет считаться подписанным."
            else -> null
        }
    }

    private suspend fun notify(houseId: Long, actId: Long, text: String, includeResidents: Boolean = false): Boolean {
        val recipients = tx {
            val chairmen = Chairmen.selectAll()
                .where { (Chairmen.houseId eq houseId) and (Chairmen.confirmedAt.isNotNull()) }
                .map { it[Chairmen.userId] }
            if (!includeResidents) chairmen
            else (chairmen + Users.selectAll().where { Users.houseId eq houseId }.map { it[Users.id] }).distinct()
        }
        val buttons = listOf(listOf(link("Открыть акт", appLink("act_$actId"))))
        var success = false
        recipients.forEach { uid ->
            runCatching { max.sendText(uid, text, buttons) }.onSuccess { success = true }
        }
        return success
    }
}
