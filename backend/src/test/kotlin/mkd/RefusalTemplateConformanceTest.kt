package mkd

import com.lowagie.text.pdf.PdfName
import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Шаблон — источник правды: тест читает docs/Шаблон_мотивированного_отказа.pdf, вырезает из него
// постоянный текст между {{ПЛЕЙСХОЛДЕРАМИ}} и требует, чтобы каждый кусок нашёлся в сгенерированном PDF.
// Поправят формулировку в шаблоне — тест упадёт, и рендерер придётся привести в соответствие.
class RefusalTemplateConformanceTest {
    private val templateFile = File("../docs/Шаблон_мотивированного_отказа.pdf")

    // Пробелы выкидываем полностью: PdfTextExtractor на подставных шрифтах шаблона иногда рвёт слова
    // («Пре дсе дате ль совета МКД»), и сверять с пробелами бессмысленно. Порядок символов при этом
    // остаётся точным, так что сравнение текста без пробелов ничего не ослабляет по сути.
    private fun squeeze(s: String): String = s.replace(Regex("\\s+"), "")

    private fun textOf(bytes: ByteArray): String {
        val reader = PdfReader(bytes)
        return squeeze(
            (1..reader.numberOfPages)
                .joinToString(" ") { PdfTextExtractor(reader).getTextFromPage(it) }
                .replace(' ', ' '),
        )
    }

    private fun generated(): ByteArray {
        val grounds = mapOf("CLEANING" to GroundRow("п. 3.2.7 ПиН 170", "уборка выполняется дважды в неделю"))
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
        return Pdf.refusal(
            RefusalPdfData(
                houseAddress = "г. Казань, ул. Демонстрационная, д. 1",
                ukName = "ООО «УК Тест»",
                ukAddress = "420000, г. Казань, ул. Управляющая, д. 10",
                ukRepresentative = "Директор Петров П. П.",
                exchangeMethod = "email: uk@test.ru",
                actNumber = "12", formedDate = LocalDate.of(2026, 9, 30), period = "сентябрь 2026",
                objections = listOf(objection), noObjectionLineNos = listOf(1, 2), photos = emptyList(),
                chairmanFio = "Сидоров С. С.", place = "г. Казань",
                composedAt = ZonedDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneId.of("Europe/Moscow")),
                demo = false,
            ),
        )
    }

    // Постоянные фрагменты шаблона: всё между {{...}}, что длиннее порога. Короткие обрывки вроде
    // « от » или «, за период с » выкидываем — они ничего не проверяют и ловятся где угодно.
    private fun templateFragments(): List<String> {
        val reader = PdfReader(templateFile.readBytes())
        val raw = (1..reader.numberOfPages).joinToString(" ") { PdfTextExtractor(reader).getTextFromPage(it) }
        val flat = raw.replace(' ', ' ').replace(Regex("\\s+"), " ")
        return flat.split(Regex("\\{\\{[^}]*}}"))
            .map { squeeze(it).trim('.', ',', ':', '—', '-') }
            .filter { it.length >= 20 }
    }

    @Test
    fun `template is readable and yields fragments`() {
        assertTrue(templateFile.isFile, "шаблон не найден: ${templateFile.absolutePath}")
        val fragments = templateFragments()
        assertTrue(fragments.size >= 6, "из шаблона извлечено слишком мало фрагментов: $fragments")
        // если извлечение текста сломается, фрагменты будут не по-русски — проверяем явно
        assertTrue(
            fragments.all { it.any { ch -> ch in 'А'..'я' } },
            "текст шаблона извлёкся нечитаемо: $fragments",
        )
    }

    @Test
    fun `generated refusal contains every constant fragment of the template`() {
        val text = textOf(generated())
        val missing = templateFragments().filterNot { text.contains(it) }
        assertTrue(missing.isEmpty(), "в сгенерированном отказе нет фрагментов шаблона:\n" + missing.joinToString("\n"))
    }

    @Test
    fun `generated refusal has no unfilled placeholders`() {
        val text = textOf(generated())
        assertTrue(!text.contains("{{") && !text.contains("}}"), "в документе остались плейсхолдеры: $text")
        // директива «повторить блок» — инструкция шаблона, в документ попадать не должна
        assertTrue(!text.contains("ПОВТОРИТЬ"), "в документ попала служебная директива шаблона")
    }

    // Вид документа: тот же формат страницы и тот же шрифт, что в шаблоне (Times New Roman по метрикам)
    @Test
    fun `page size matches the template`() {
        val template = PdfReader(templateFile.readBytes()).getPageSize(1)
        val actual = PdfReader(generated()).getPageSize(1)
        // шаблон прогнали через конвертер, и он выдал A4 как 595.2×841.92 вместо 595×842 — допуск 1 pt
        assertTrue(
            kotlin.math.abs(template.width - actual.width) <= 1f &&
                kotlin.math.abs(template.height - actual.height) <= 1f,
            "формат страницы: шаблон ${template.width}×${template.height}, документ ${actual.width}×${actual.height}",
        )
    }

    // Образец для глазной сверки с шаблоном (как DemoActPdfTest — обычный прогон его не пишет):
    // GEN_DEMO=1 ./gradlew test --tests mkd.RefusalTemplateConformanceTest
    @Test
    fun `generate refusal sample pdf`() {
        if (System.getenv("GEN_DEMO") != "1") return
        File("../demo").mkdirs()
        File("../demo/refusal-demo.pdf").writeBytes(generated())
        println("сверяемых фрагментов шаблона: " + templateFragments().size)
        templateFragments().forEach { println("  • $it") }
    }

    @Test
    fun `document is typeset in times new roman metrics`() {
        val fonts = PdfReader(generated()).getPageN(1)
            .getAsDict(PdfName.RESOURCES)
            .getAsDict(PdfName.FONT)
        val names = fonts.keys.mapNotNull { fonts.getAsDict(it)?.get(PdfName.BASEFONT)?.toString() }
        assertTrue(names.isNotEmpty(), "в документе нет шрифтов")
        assertTrue(
            names.all { it.contains("Tinos", ignoreCase = true) },
            "документ должен быть набран Tinos (метрический клон Times New Roman), а не $names",
        )
    }
}
