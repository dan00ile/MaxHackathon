package mkd

import kotlinx.coroutines.delay
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
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

class TimerService(private val cfg: Config, private val max: MaxBotClient, private val clock: Clock = Clock.systemUTC()) {
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
                .where { Acts.status inList listOf(ActStatus.RECEIVED, ActStatus.COLLECTING, ActStatus.REVIEW) }
                .toList()
        }
        for (act in acts) {
            val actId = act[Acts.id].value
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
    }

    private fun notifyText(type: String, act: ResultRow, now: Instant): String? {
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val d10 = fmt.format(act[Acts.deadline10])
        val d30 = fmt.format(act[Acts.deadline30])
        val n30 = Deadlines.daysLeft(act[Acts.deadline30], now, cfg.zone)
        return when (type) {
            "NOTIFY_D7" -> "Через 3 дня, $d10, заканчивается срок по приказу на подписание или отказ. " +
                "Посмотрите замечания жителей и примите решение."
            "NOTIFY_D10" -> "Сегодня, $d10, последний день срока по приказу. Если не успеваете — подписать или отказать " +
                "ещё можно до $d30; после этого акт будет считаться подписанным без ваших возражений."
            "NOTIFY_D25" -> "Осталось дней: $n30. После $d30 акт будет считаться подписанным без ваших возражений."
            "NOTIFY_D28" -> "Внимание: осталось дней: $n30. После $d30 акт будет считаться подписанным."
            "NOTIFY_D29" -> "Внимание: завтра, $d30, последний день. Если ничего не сделать, акт будет считаться подписанным."
            else -> null
        }
    }

    private suspend fun notify(houseId: Long, actId: Long, text: String): Boolean {
        val chairmen = tx {
            Chairmen.selectAll()
                .where { (Chairmen.houseId eq houseId) and (Chairmen.confirmedAt.isNotNull()) }
                .map { it[Chairmen.userId] }
        }
        val buttons = listOf(listOf(link("Открыть акт", appLink("act_$actId"))), listOf(cb("Статус", "status")))
        var success = false
        chairmen.forEach { uid ->
            runCatching { max.sendText(uid, text, buttons) }.onSuccess { success = true }
        }
        return success
    }
}
