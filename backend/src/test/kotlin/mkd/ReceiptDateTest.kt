package mkd

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReceiptDateTest {
    private val today = LocalDate.of(2026, 9, 27)

    @Test
    fun `dates inside the 30-day window pass, both edges included`() {
        checkReceiptDate(today, today)
        checkReceiptDate(today.minusDays(30), today)
    }

    @Test
    fun `future date is rejected with a retry hint`() {
        val error = assertFailsWith<ApiError> { checkReceiptDate(today.plusDays(1), today) }
        assertEquals("invalid_date", error.code)
        assertTrue("позже сегодняшней" in error.message)
        assertTrue("напишите правильную" in error.message)
    }

    @Test
    fun `date older than 30 days explains silent consent`() {
        val error = assertFailsWith<ApiError> { checkReceiptDate(today.minusDays(31), today) }
        assertEquals("invalid_date", error.code)
        assertTrue("318/пр" in error.message, "объясняем, почему бот тут уже не поможет")
        assertTrue("напишите правильную" in error.message)
    }
}
