package mkd

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class TimersTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val receivedAt = OffsetDateTime.of(2026, 9, 24, 9, 0, 0, 0, ZoneOffset.ofHours(3)).toInstant()

    @Test fun `just after receipt sends D0`() {
        val now = receivedAt.plusSeconds(3600)
        val plan = planNotifications(receivedAt, emptySet(), now, zone)
        assertEquals(emptyList(), plan.skip)
        assertEquals("NOTIFY_D0", plan.send)
    }

    @Test fun `on day plus 7 sends D7 with nothing to skip`() {
        val now = OffsetDateTime.of(2026, 10, 1, 10, 5, 0, 0, ZoneOffset.ofHours(3)).toInstant()
        val plan = planNotifications(receivedAt, setOf("NOTIFY_D0"), now, zone)
        assertEquals(emptyList(), plan.skip)
        assertEquals("NOTIFY_D7", plan.send)
    }

    @Test fun `after server sleep skips stale milestones and sends the freshest`() {
        val now = OffsetDateTime.of(2026, 10, 20, 10, 5, 0, 0, ZoneOffset.ofHours(3)).toInstant()
        val plan = planNotifications(receivedAt, setOf("NOTIFY_D0"), now, zone)
        assertEquals(listOf("NOTIFY_D7", "NOTIFY_D10"), plan.skip)
        assertEquals("NOTIFY_D25", plan.send)
    }

    @Test fun `nothing due yet sends nothing`() {
        val now = OffsetDateTime.of(2026, 10, 1, 9, 59, 0, 0, ZoneOffset.ofHours(3)).toInstant()
        val plan = planNotifications(receivedAt, setOf("NOTIFY_D0"), now, zone)
        assertEquals(emptyList(), plan.skip)
        assertEquals(null, plan.send)
    }
}
