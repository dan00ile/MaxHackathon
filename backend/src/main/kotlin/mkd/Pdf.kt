package mkd

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.Image
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.BaseFont
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ItemRow(val lineNo: Int, val name: String, val periodicity: String, val volume: String, val cost: String)
data class SignedActData(
    val houseAddress: String, val ukName: String, val actNumber: String?, val formedDate: LocalDate?,
    val period: String?, val items: List<ItemRow>, val chairmanFio: String, val signedAt: ZonedDateTime, val demo: Boolean,
)

data class PhotoPage(
    val registryNo: Int, val lineNo: Int, val itemName: String, val author: String,
    val uploadedAt: ZonedDateTime, val file: File,
)
data class RefusalPdfData(
    val houseAddress: String, val ukName: String, val ukRepresentative: String, val exchangeMethod: String,
    val actNumber: String?, val formedDate: LocalDate?, val period: String?,
    val objections: List<Objection>, val noObjectionLineNos: List<Int>,
    val photos: List<PhotoPage>,
    val chairmanFio: String, val place: String, val composedAt: ZonedDateTime, val demo: Boolean,
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

    fun refusal(d: RefusalPdfData): ByteArray {
        val bold = font("DejaVuSans-Bold.ttf", 14f)
        val regular = font("DejaVuSans.ttf", 11f)
        val small = font("DejaVuSans.ttf", 9f)
        val labelFont = font("DejaVuSans-Bold.ttf", 11f)

        val out = ByteArrayOutputStream()
        val document = Document()
        PdfWriter.getInstance(document, out)
        document.open()

        listOf(
            "Исполнителю: ${d.ukName}",
            d.ukRepresentative,
            " ",
            "от председателя совета МКД по адресу: ${d.houseAddress}, ${d.chairmanFio}",
        ).forEach { line ->
            val p = Paragraph(line, regular)
            p.alignment = Element.ALIGN_RIGHT
            document.add(p)
        }
        document.add(Paragraph(" "))

        document.add(
            Paragraph(
                "Мотивированный отказ от подписания акта приёмки оказанных услуг и (или) выполненных работ по " +
                    "содержанию и текущему ремонту общего имущества в многоквартирном доме",
                bold,
            ),
        )
        document.add(Paragraph(" "))
        document.add(
            Paragraph(
                "В соответствии с п. 4 Порядка приёмки оказанных услуг и (или) выполненных работ по содержанию и " +
                    "текущему ремонту общего имущества в многоквартирном доме, утв. приказом Минстроя России от " +
                    "22.05.2026 № 318/пр, отказываюсь от подписания акта № ${d.actNumber ?: "без номера"} от " +
                    "${d.formedDate?.format(dateFmt) ?: "—"} за ${d.period ?: "—"} в части следующих позиций:",
                regular,
            ),
        )
        document.add(Paragraph(" "))

        fun labelRow(table: PdfPTable, label: String, value: String) {
            table.addCell(PdfPCell(Paragraph(label, labelFont)))
            table.addCell(PdfPCell(Paragraph(value, regular)))
        }
        d.objections.forEach { o ->
            val table = PdfPTable(2)
            table.widthPercentage = 100f
            table.setWidths(floatArrayOf(30f, 70f))
            labelRow(table, "Позиция акта", "№${o.lineNo}. ${o.itemName}")
            labelRow(table, "Фактически", o.fact)
            labelRow(table, "Основание", "${o.groundText} (${o.groundRef})")
            labelRow(table, "Требование", o.demand)
            labelRow(table, "Отметки жителей", "выполнено: ${o.okCount}, претензия: ${o.issueCount}, фото: ${o.photoCount}")
            val appendixNos = d.photos.filter { it.lineNo == o.lineNo }.map { it.registryNo }
            labelRow(table, "Приложения", if (appendixNos.isEmpty()) "—" else appendixNos.joinToString(", ") { "№$it" })
            document.add(table)
            document.add(Paragraph(" "))
        }

        if (d.noObjectionLineNos.isNotEmpty()) {
            document.add(Paragraph("По позициям № ${d.noObjectionLineNos.joinToString(", ")} возражений не имеется.", regular))
            document.add(Paragraph(" "))
        }

        document.add(Paragraph("Настоящий отказ направляется исполнителю способом: ${d.exchangeMethod}.", regular))
        document.add(Paragraph(" "))
        document.add(Paragraph("Место составления: ${d.place}. Дата и время составления: ${d.composedAt.format(dateTimeFmt)}.", regular))
        document.add(Paragraph("Председатель совета МКД ____________ /${d.chairmanFio}/", regular))

        if (d.demo) {
            document.add(Paragraph(" "))
            val demoNote = Paragraph(
                "Демо-режим: документ сформирован без квалифицированной электронной подписи (Госключ не подключён). " +
                    "Сведения об УК и справочник оснований — демонстрационные.",
                Font(small.baseFont, small.size, Font.ITALIC),
            )
            document.add(demoNote)
        }

        if (d.photos.isNotEmpty()) {
            document.newPage()
            document.add(Paragraph("Реестр приложений", bold))
            document.add(Paragraph(" "))
            val registry = PdfPTable(4)
            registry.widthPercentage = 100f
            listOf("№", "Позиция", "Автор", "Дата загрузки").forEach { registry.addCell(PdfPCell(Paragraph(it, labelFont))) }
            d.photos.forEach { p ->
                registry.addCell(PdfPCell(Paragraph(p.registryNo.toString(), regular)))
                registry.addCell(PdfPCell(Paragraph("№${p.lineNo}. ${p.itemName}", regular)))
                registry.addCell(PdfPCell(Paragraph(p.author, regular)))
                registry.addCell(PdfPCell(Paragraph(p.uploadedAt.format(dateTimeFmt), regular)))
            }
            document.add(registry)

            d.photos.forEach { p ->
                document.newPage()
                document.add(
                    Paragraph(
                        "Приложение № ${p.registryNo}. Позиция № ${p.lineNo} «${p.itemName}». " +
                            "Автор: ${p.author}. Загружено: ${p.uploadedAt.format(dateTimeFmt)}",
                        regular,
                    ),
                )
                document.add(Paragraph(" "))
                val image = Image.getInstance(p.file.path)
                val maxWidth = document.pageSize.width - document.leftMargin() - document.rightMargin()
                val maxHeight = document.pageSize.height - 120
                image.scaleToFit(maxWidth, maxHeight)
                document.add(image)
            }
        }

        document.close()
        return out.toByteArray()
    }
}
