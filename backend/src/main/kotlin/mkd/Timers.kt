package mkd

import kotlinx.coroutines.delay
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

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
            // ponytail: тексты и получатели уведомлений появятся в S13
            if (notify(act, type)) {
                tx { logEvent(actId, type, null, "sent") }
            }
        }
    }

    private suspend fun notify(act: ResultRow, type: String): Boolean = false
}
