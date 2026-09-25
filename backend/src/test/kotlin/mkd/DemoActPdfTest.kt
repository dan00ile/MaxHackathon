package mkd

import com.lowagie.text.Document
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import java.io.FileOutputStream
import kotlin.test.Test

// Генератор демо-акта для проверки распознавания (S10). Обычный `./gradlew test` его пропускает —
// файл пишется только по явной команде: GEN_DEMO=1 ./gradlew test --tests mkd.DemoActPdfTest
class DemoActPdfTest {
    @Test
    fun `generate demo act pdf`() {
        if (System.getenv("GEN_DEMO") != "1") return

        val document = Document()
        PdfWriter.getInstance(document, FileOutputStream("../demo/act-demo.pdf"))
        document.open()

        val bold = Pdf.font("DejaVuSans-Bold.ttf", 13f)
        val regular = Pdf.font("DejaVuSans.ttf", 11f)
        val small = Pdf.font("DejaVuSans.ttf", 9f)

        document.add(
            Paragraph(
                "Акт приёмки оказанных услуг и (или) выполненных работ по содержанию и текущему " +
                    "ремонту общего имущества в многоквартирном доме",
                bold,
            ),
        )
        document.add(Paragraph("№ 12 от 30.09.2026, период: сентябрь 2026", regular))
        document.add(Paragraph("Адрес: г. Казань, ул. Демонстрационная, д. 1", regular))
        document.add(Paragraph("Исполнитель: ООО «УК Демо-Сервис»", regular))
        document.add(Paragraph(" "))

        val table = PdfPTable(5)
        table.widthPercentage = 100f
        listOf("№", "Наименование", "Периодичность", "Ед. изм./объём", "Стоимость, руб.").forEach {
            table.addCell(PdfPCell(Paragraph(it, bold)))
        }

        data class Row(val name: String, val periodicity: String, val volume: String, val cost: String)
        listOf(
            Row("Влажная уборка лестничных клеток", "1 раз в неделю", "1 200 м²", "18 000"),
            Row("Уборка придомовой территории", "ежедневно", "2 500 м²", "25 000"),
            Row("Содержание контейнерной площадки", "ежедневно", "1 шт.", "4 000"),
            Row("Осмотр кровли", "2 раза в год", "900 м²", "3 500"),
            Row("Техническое обслуживание лифта", "ежемесячно", "3 шт.", "21 000"),
            Row("Осмотр системы отопления", "1 раз в месяц", "1 система", "6 000"),
        ).forEachIndexed { idx, row ->
            table.addCell(PdfPCell(Paragraph((idx + 1).toString(), regular)))
            table.addCell(PdfPCell(Paragraph(row.name, regular)))
            table.addCell(PdfPCell(Paragraph(row.periodicity, regular)))
            table.addCell(PdfPCell(Paragraph(row.volume, regular)))
            table.addCell(PdfPCell(Paragraph(row.cost, regular)))
        }
        document.add(table)
        document.add(Paragraph(" "))
        document.add(Paragraph("ДЕМОНСТРАЦИОННЫЙ ДОКУМЕНТ", small))
        document.close()
    }
}
