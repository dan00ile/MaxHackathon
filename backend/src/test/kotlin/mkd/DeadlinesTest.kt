package mkd

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class DeadlinesTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val receivedAt = OffsetDateTime.of(2026, 9, 24, 9, 0, 0, 0, ZoneOffset.ofHours(3)).toInstant()

    @Test fun `day10 is ten days later`() {
        assertEquals(LocalDate.of(2026, 10, 4), Deadlines.day10(receivedAt, zone))
    }

    @Test fun `day30 is thirty days later`() {
        assertEquals(LocalDate.of(2026, 10, 24), Deadlines.day30(receivedAt, zone))
    }

    @Test fun `silentAt is start of the day after deadline30`() {
        val expected = OffsetDateTime.of(2026, 10, 25, 0, 0, 0, 0, ZoneOffset.ofHours(3)).toInstant()
        assertEquals(expected, Deadlines.silentAt(LocalDate.of(2026, 10, 24), zone))
    }

    @Test fun `milestoneAt d7 is 10 00 local time seven days later`() {
        val expected = OffsetDateTime.of(2026, 10, 1, 10, 0, 0, 0, ZoneOffset.ofHours(3)).toInstant()
        assertEquals(expected, Deadlines.milestoneAt(receivedAt, 7, zone))
    }

    @Test fun `milestoneAt d0 is receivedAt itself`() {
        assertEquals(receivedAt, Deadlines.milestoneAt(receivedAt, 0, zone))
    }

    @Test fun `daysLeft is zero on the deadline day`() {
        val now = OffsetDateTime.of(2026, 10, 4, 15, 0, 0, 0, ZoneOffset.ofHours(3)).toInstant()
        assertEquals(0L, Deadlines.daysLeft(LocalDate.of(2026, 10, 4), now, zone))
    }
}
