package mkd

import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefusalDraftTest {
    private val grounds = mapOf(
        "CLEANING" to GroundRow("ссылка CLEANING", "Уборка выполняется дважды в неделю"),
        "OTHER" to GroundRow("ссылка OTHER", "Работы выполняются по договору"),
    )

    @Test
    fun `dispute with photo becomes objection with merged fact`() {
        val items = listOf(
            DraftItem(
                itemId = 1, lineNo = 1, name = "Уборка лестниц", workKind = "CLEANING", decision = Decision.DISPUTE,
                remarks = listOf(
                    DraftRemark(Verdict.ISSUE, "Не мыли неделю", 1),
                    DraftRemark(Verdict.ISSUE, "Грязно на 3 этаже", 1),
                    DraftRemark(Verdict.OK, "", 0),
                ),
                periodicity = "2 раза в неделю", volume = "1 200 м²", cost = "18 000",
            ),
        )
        val draft = buildDraft(items, grounds)
        assertEquals(1, draft.objections.size)
        val o = draft.objections.single()
        assertEquals("1) Не мыли неделю\n2) Грязно на 3 этаже", o.fact)
        assertEquals(1, o.okCount)
        assertEquals(2, o.issueCount)
        assertEquals(2, o.photoCount)
        assertEquals("ссылка CLEANING", o.groundRef)
        assertEquals(
            "«Уборка лестниц», периодичность — 2 раза в неделю, объём — 1 200 м², стоимость — 18 000 руб.",
            o.actWording,
        )
        assertEquals(
            "Работа не выполнена или выполнена с недостатками, тогда как уборка выполняется дважды в неделю (ссылка CLEANING).",
            o.demand,
        )
        assertTrue(draft.noObjectionLineNos.isEmpty())
    }

    @Test
    fun `act wording skips empty columns`() {
        assertEquals("«Двор»", actWording(DraftItem(2, 2, "Двор", "YARD", null, emptyList())))
    }

    @Test
    fun `month period becomes date range`() {
        assertEquals("за период с 01.08.2026 по 31.08.2026", periodPhrase("август 2026"))
        assertEquals("за период с 01.02.2028 по 29.02.2028", periodPhrase("Февраль 2028 (ремонт)"))
        assertEquals("за период III квартал 2026", periodPhrase("III квартал 2026"))
        assertEquals("за период —", periodPhrase(null))
    }

    // PDF собирается по шаблону мотивированного отказа — проверяем по извлечённому тексту
    @Test
    fun `refusal pdf follows the template`() {
        val objection = buildDraft(
            listOf(
                DraftItem(
                    1, 3, "Уборка лестниц", "CLEANING", Decision.DISPUTE,
                    listOf(DraftRemark(Verdict.ISSUE, "Не мыли неделю.", 1), DraftRemark(Verdict.OK, "", 0)),
                    periodicity = "2 раза в неделю",
                ),
            ),
            grounds,
        ).objections.single()
        val bytes = Pdf.refusal(
            RefusalPdfData(
                houseAddress = "г. Казань, ул. Демонстрационная, д. 1", ukName = "ООО «УК Тест»",
                ukRepresentative = "Директор Петров П. П.", exchangeMethod = "email: uk@test.ru",
                actNumber = "12", formedDate = LocalDate.of(2026, 9, 30), period = "сентябрь 2026",
                objections = listOf(objection), noObjectionLineNos = listOf(1, 2), photos = emptyList(),
                chairmanFio = "Сидоров С. С.", place = "г. Казань",
                composedAt = ZonedDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneId.of("Europe/Moscow")), demo = false,
            ),
        )
        val reader = PdfReader(bytes)
        val text = (1..reader.numberOfPages).joinToString("\n") { PdfTextExtractor(reader).getTextFromPage(it) }
            .replace(Regex("\\s+"), " ")

        listOf(
            "В ООО «УК Тест»",
            "от председателя совета многоквартирного дома",
            "Сидоров С. С., г. Казань, ул. Демонстрационная, д. 1",
            "Мотивированный отказ от подписания акта приёмки оказанных услуг (выполненных работ)",
            "№ 12 от 30.09.2026",
            "за период с 01.09.2026 по 30.09.2026",
            "3. Уборка лестниц",
            "Указано в акте: «Уборка лестниц», периодичность — 2 раза в неделю.",
            "По данным опроса жильцов: Не мыли неделю (отметили выполнение — 1 из 2 опрошенных).",
            "Приложены фотоматериалы: нет.",
            "Возражение: Работа не выполнена или выполнена с недостатками",
            "По позициям № 1, 2 возражений не имеется.",
            "установленном пунктом 6 Порядка",
            "Дата: 02.10.2026",
            "Председатель совета МКД: Сидоров С. С.",
            "Подпись: _______________",
        ).forEach { assertContains(text, it) }
    }

    @Test
    fun `dispute without photo has no objection`() {
        val items = listOf(DraftItem(2, 2, "Двор", "YARD", Decision.DISPUTE, listOf(DraftRemark(Verdict.ISSUE, "Мусор", 0))))
        val draft = buildDraft(items, grounds)
        assertTrue(draft.objections.isEmpty())
        assertEquals(listOf(2), draft.noObjectionLineNos)
    }

    @Test
    fun `accepted item has no objection`() {
        val items = listOf(DraftItem(3, 3, "Лифт", "ELEVATOR", Decision.ACCEPT, listOf(DraftRemark(Verdict.OK, "", 0))))
        val draft = buildDraft(items, grounds)
        assertTrue(draft.objections.isEmpty())
        assertEquals(listOf(3), draft.noObjectionLineNos)
    }

    @Test
    fun `unknown work kind falls back to OTHER ground`() {
        val items = listOf(DraftItem(4, 4, "Прочее", "NOPE", Decision.DISPUTE, listOf(DraftRemark(Verdict.ISSUE, "Проблема", 1))))
        val draft = buildDraft(items, grounds)
        assertEquals(1, draft.objections.size)
        assertEquals("ссылка OTHER", draft.objections.single().groundRef)
    }
}
