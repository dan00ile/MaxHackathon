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
    fun `date older than 30 days says the term expired and offers another act`() {
        val error = assertFailsWith<ApiError> { checkReceiptDate(today.minusDays(31), today) }
        assertEquals("invalid_date", error.code)
        assertTrue("Срок по этому акту истёк" in error.message)
        assertTrue("318/пр" in error.message, "объясняем, почему бот тут уже не поможет")
        assertTrue("загрузите его файлом" in error.message, "предлагаем загрузить другой акт")
        assertTrue("напишите правильную" in error.message, "опечатку в дате ещё можно исправить")
    }
}
