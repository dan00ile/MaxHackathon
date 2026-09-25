package mkd

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.BaseFont
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ItemRow(val lineNo: Int, val name: String, val periodicity: String, val volume: String, val cost: String)
data class SignedActData(
    val houseAddress: String, val ukName: String, val actNumber: String?, val formedDate: LocalDate?,
    val period: String?, val items: List<ItemRow>, val chairmanFio: String, val signedAt: ZonedDateTime, val demo: Boolean,
)

private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

object Pdf {
    private val baseFonts = mutableMapOf<String, BaseFont>()

    private fun baseFont(name: String): BaseFont = synchronized(baseFonts) {
        baseFonts.getOrPut(name) {
            val bytes = Pdf::class.java.getResourceAsStream("/fonts/$name")!!.readBytes()
            BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null)
        }
    }

    fun font(name: String, size: Float): Font = Font(baseFont(name), size)

    fun signedAct(d: SignedActData): ByteArray {
        val bold = font("DejaVuSans-Bold.ttf", 14f)
        val regular = font("DejaVuSans.ttf", 11f)
        val small = font("DejaVuSans.ttf", 9f)

        val out = ByteArrayOutputStream()
        val document = Document()
        PdfWriter.getInstance(document, out)
        document.open()

        document.add(
            Paragraph(
                "Экземпляр акта приёмки оказанных услуг и (или) выполненных работ по содержанию и " +
                    "текущему ремонту общего имущества в многоквартирном доме",
                bold,
            ),
        )
        document.add(Paragraph(" "))
        document.add(Paragraph("Акт № ${d.actNumber ?: "без номера"} от ${d.formedDate?.format(dateFmt) ?: "—"} за ${d.period ?: "—"}", regular))
        document.add(Paragraph("Адрес: ${d.houseAddress}", regular))
        document.add(Paragraph("Исполнитель: ${d.ukName}", regular))
        document.add(Paragraph(" "))

        val table = PdfPTable(5)
        table.widthPercentage = 100f
        listOf("№", "Наименование", "Периодичность", "Ед. изм./объём", "Стоимость, руб.").forEach {
            table.addCell(PdfPCell(Paragraph(it, bold)))
        }
        d.items.forEach { item ->
            table.addCell(PdfPCell(Paragraph(item.lineNo.toString(), regular)))
            table.addCell(PdfPCell(Paragraph(item.name, regular)))
            table.addCell(PdfPCell(Paragraph(item.periodicity, regular)))
            table.addCell(PdfPCell(Paragraph(item.volume, regular)))
            table.addCell(PdfPCell(Paragraph(item.cost, regular)))
        }
        document.add(table)
        document.add(Paragraph(" "))

        document.add(Paragraph("Акт подписан председателем совета многоквартирного дома без возражений.", regular))
        document.add(Paragraph("Председатель совета МКД: ${d.chairmanFio} ____________", regular))
        document.add(Paragraph("Дата и время подписания: ${d.signedAt.format(dateTimeFmt)}", regular))

        if (d.demo) {
            document.add(Paragraph(" "))
            val demoNote = Paragraph(
                "Демо-режим: документ сформирован без квалифицированной электронной подписи (Госключ не подключён).",
                Font(small.baseFont, small.size, Font.ITALIC),
            )
            demoNote.alignment = Element.ALIGN_LEFT
            document.add(demoNote)
        }

        document.close()
        return out.toByteArray()
    }
}
