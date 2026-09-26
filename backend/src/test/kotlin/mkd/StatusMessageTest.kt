package mkd

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.ResultRow
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Сообщение о статусе собирается из строки акта и ничего не читает из БД, поэтому строку подделываем
class StatusMessageTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val now = OffsetDateTime.of(2026, 9, 26, 12, 0, 0, 0, ZoneOffset.ofHours(3)).toInstant()
    private val address = "г. Казань, ул. Демонстрационная, д. 1"

    @BeforeTest
    fun setUp() {
        botUsername = "mkd_test_bot"
    }

    private fun actRow(status: ActStatus) = ResultRow.createAndFillValues(
        mapOf<Expression<*>, Any?>(
            Acts.id to EntityID(12L, Acts),
            Acts.number to "12",
            Acts.period to "сентябрь 2026",
            Acts.status to status,
            Acts.deadline10 to LocalDate.of(2026, 10, 6),
            Acts.deadline30 to LocalDate.of(2026, 10, 26),
        )
    )

    private fun links(buttons: List<List<Button>>) = buttons.flatten().filter { it.type == "link" }
    private fun payloads(buttons: List<List<Button>>) = buttons.flatten().mapNotNull { it.payload }

    // главный регресс: у жителя по завершённому акту не было ни одной кнопки, мини-апп из чата не открывался
    @Test fun `finished act still offers the mini app link`() {
        listOf(ActStatus.SIGNED, ActStatus.REJECTED, ActStatus.SILENT).forEach { status ->
            val buttons = statusButtons(actRow(status), isChairman = false)
            assertEquals(
                listOf(Button("link", "Открыть акт", url = "https://max.ru/mkd_test_bot?startapp=act_12")),
                links(buttons),
                "ссылка на мини-апп нужна и при статусе $status",
            )
            assertEquals(listOf("status"), payloads(buttons), "жителю остаётся только обновить статус")
        }
    }

    @Test fun `chairman keeps decision buttons while collecting`() {
        val buttons = statusButtons(actRow(ActStatus.COLLECTING), isChairman = true)
        assertEquals(1, links(buttons).size)
        assertEquals(listOf("close:12", "sign:12", "refuse:12", "status"), payloads(buttons))
    }

    // решения по акту вне активных статусов уже приняты — кнопки председателя не возвращаем
    @Test fun `chairman has no decision buttons on a signed act`() {
        val buttons = statusButtons(actRow(ActStatus.SIGNED), isChairman = true)
        assertEquals(1, links(buttons).size)
        assertEquals(listOf("status"), payloads(buttons))
    }

    @Test fun `finished act text shows the terminal event date instead of countdowns`() {
        val signedAt = OffsetDateTime.of(2026, 9, 26, 9, 0, 0, 0, ZoneOffset.ofHours(3)).toInstant()
        val text = statusText(actRow(ActStatus.SIGNED), address, now, zone, signedAt)

        assertContains(text, "Акт № 12 за сентябрь 2026, г. Казань, ул. Демонстрационная, д. 1")
        assertContains(text, "Статус: Подписан")
        assertContains(text, "Дата: 26.09.2026")
        assertTrue("осталось дней" !in text, "по завершённому акту обратный отсчёт не показываем")
    }

    // приветствие без статуса означало бы «Открыть акт» без указания, какой акт откроется:
    // такое сообщение переживает акт и потом ведёт на прошлый
    @Test fun `menu text carries the act it links to`() {
        val text = menuText(actRow(ActStatus.REJECTED), address, isChairman = false, now = now, zone = zone)

        assertContains(text, "Отслеживайте здесь проверку акта работ по вашему дому.")
        assertContains(text, "Акт № 12 за сентябрь 2026")
        assertContains(text, "Статус: Отказ направлен")
    }

    @Test fun `menu text without an act is just the greeting`() {
        assertEquals(
            "Акт присылайте сюда файлом — PDF или фото.",
            menuText(null, address, isChairman = true, now = now, zone = zone),
        )
        assertEquals(
            "Отслеживайте здесь проверку акта работ по вашему дому.",
            menuText(null, address, isChairman = false, now = now, zone = zone),
        )
    }

    @Test fun `active act text shows both deadlines`() {
        val text = statusText(actRow(ActStatus.COLLECTING), address, now, zone)

        assertContains(text, "Статус: Идёт сбор замечаний")
        assertContains(text, "Срок по приказу (10 дней): до 06.10.2026 — осталось дней: 10")
        assertContains(text, "Защитный срок (30 дней): до 26.10.2026 — осталось дней: 30")
    }
}
