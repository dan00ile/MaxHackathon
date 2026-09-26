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
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ItemRow(val lineNo: Int, val name: String, val periodicity: String, val volume: String, val cost: String)
data class SignedActData(
    val houseAddress: String,
    val ukName: String,
    val actNumber: String?,
    val formedDate: LocalDate?,
    val period: String?,
    val items: List<ItemRow>,
    val chairmanFio: String,
    val signedAt: ZonedDateTime,
    val demo: Boolean,
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

private const val ORDER_318 = "приказом Минстроя России от 22.05.2026 № 318/пр"

private val months = listOf(
    "январь", "февраль", "март", "апрель", "май", "июнь",
    "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
)

// Период акта обычно записан как «август 2026» — в отказе нужны даты «с … по …».
// Если распознать месяц не вышло, пишем период как в акте
fun periodPhrase(period: String?): String {
    val m = Regex("""^\s*(\p{L}+)\s+(\d{4})""").find(period ?: "")
    val month = m?.let { months.indexOf(it.groupValues[1].lowercase()) } ?: -1
    if (m == null || month < 0) return "за период ${period?.ifBlank { null } ?: "—"}"
    val ym = YearMonth.of(m.groupValues[2].toInt(), month + 1)
    return "за период с ${ym.atDay(1).format(dateFmt)} по ${ym.atEndOfMonth().format(dateFmt)}"
}

// текст из поля ввода как законченное предложение: точка в конце ровно одна
fun sentence(text: String): String = text.trim().let { if (it.isEmpty() || it.last() in ".!?") it else "$it." }

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
        document.add(
            Paragraph(
                "Акт № ${d.actNumber ?: "без номера"} от ${d.formedDate?.format(dateFmt) ?: "—"} за ${d.period ?: "—"}",
                regular
            )
        )
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

    // Структура — по шаблону мотивированного отказа (Шаблон_мотивированного_отказа.docx): шапка «кому/от кого»,
    // вводный абзац по п. 4 Порядка, блок на каждую спорную позицию, просьба оформить новый акт по п. 6, подпись.
    // Сверх шаблона — обязательные реквизиты FR-G2: способ уведомления УК, место составления, реестр фото
    fun refusal(d: RefusalPdfData): ByteArray {
        val bold = font("DejaVuSans-Bold.ttf", 14f)
        val regular = font("DejaVuSans.ttf", 11f)
        val small = font("DejaVuSans.ttf", 9f)
        val labelFont = font("DejaVuSans-Bold.ttf", 11f)

        val out = ByteArrayOutputStream()
        val document = Document()
        PdfWriter.getInstance(document, out)
        document.open()
        fun para(text: String, font: Font = regular, align: Int = Element.ALIGN_LEFT, before: Float = 0f) =
            document.add(Paragraph(text, font).apply { alignment = align; spacingBefore = before })

        listOf("В ${d.ukName}", d.ukRepresentative, d.exchangeMethod).forEach { para(it, align = Element.ALIGN_RIGHT) }
        para("от председателя совета многоквартирного дома", align = Element.ALIGN_RIGHT, before = 6f)
        para("${d.chairmanFio}, ${d.houseAddress}", align = Element.ALIGN_RIGHT)

        para(
            "Мотивированный отказ от подписания акта приёмки оказанных услуг (выполненных работ)",
            bold, Element.ALIGN_CENTER, before = 18f,
        )
        val actDate = d.formedDate?.let { " от ${it.format(dateFmt)}" } ?: ""
        para(
            "В соответствии с пунктом 4 Порядка, утверждённого $ORDER_318, сообщаю об отказе от подписания " +
                "акта приёмки оказанных услуг (выполненных работ) № ${d.actNumber ?: "без номера"}$actDate " +
                "по содержанию и текущему ремонту общего имущества многоквартирного дома по адресу: " +
                "${d.houseAddress}, ${periodPhrase(d.period)}.",
            before = 12f,
        )
        para(
            "Отказ от подписания акта мотивирован следующими аргументированными возражениями против его содержания, " +
                "установленными на основании опроса собственников и пользователей помещений многоквартирного дома:",
            before = 6f,
        )

        d.objections.forEach { o ->
            para("${o.lineNo}. ${o.itemName}", labelFont, before = 10f)
            para("Указано в акте: ${sentence(o.actWording.ifBlank { "«${o.itemName}»" })}")
            para(
                "По данным опроса жильцов: ${o.fact.trimEnd().trimEnd('.')} (отметили выполнение — " +
                    "${o.okCount} из ${o.okCount + o.issueCount} опрошенных).",
            )
            val appendixNos = d.photos.filter { it.lineNo == o.lineNo }.map { it.registryNo }
            para(
                "Приложены фотоматериалы: " +
                    if (appendixNos.isEmpty()) "нет." else "приложения № ${appendixNos.joinToString(", ")}.",
            )
            para("Возражение: ${sentence(o.demand)}")
        }

        if (d.noObjectionLineNos.isNotEmpty()) {
            para("По позициям № ${d.noObjectionLineNos.joinToString(", ")} возражений не имеется.", before = 10f)
        }
        para(
            "На основании изложенного прошу устранить указанные замечания и оформить новый акт приёмки в порядке, " +
                "установленном пунктом 6 Порядка, утверждённого $ORDER_318.",
            before = 10f,
        )
        para("Настоящий отказ направляется исполнителю: ${d.exchangeMethod}.", before = 6f)

        para("Дата: ${d.composedAt.format(dateFmt)}", before = 18f)
        para("Место составления: ${d.place}")
        para("Председатель совета МКД: ${d.chairmanFio}")
        para("Подпись: _______________", before = 6f)

        if (d.demo) {
            document.add(Paragraph(" "))
            val demoNote = Paragraph(
                "Демо-режим: документ сформирован без квалифицированной электронной подписи (Госключ не подключён). " +
                        "Сведения об УК и справочник оснований — демонстрационные.",
                Font(small.baseFont, small.size, Font.ITALIC),
            )
            document.add(demoNote)
        }

        if (d.photos.isNotEmpty()) appendix(document, d.photos, bold, regular, labelFont)

        document.close()
        return out.toByteArray()
    }

    // реестр фото и по странице на каждое фото — на них ссылаются «Приложены фотоматериалы» в блоках позиций
    private fun appendix(document: Document, photos: List<PhotoPage>, bold: Font, regular: Font, labelFont: Font) {
        document.newPage()
        document.add(Paragraph("Реестр приложений", bold))
        document.add(Paragraph(" "))
        val registry = PdfPTable(4)
        registry.widthPercentage = 100f
        listOf("№", "Позиция", "Автор", "Дата загрузки").forEach {
            registry.addCell(
                PdfPCell(
                    Paragraph(
                        it,
                        labelFont
                    )
                )
            )
        }
        photos.forEach { p ->
            registry.addCell(PdfPCell(Paragraph(p.registryNo.toString(), regular)))
            registry.addCell(PdfPCell(Paragraph("№${p.lineNo}. ${p.itemName}", regular)))
            registry.addCell(PdfPCell(Paragraph(p.author, regular)))
            registry.addCell(PdfPCell(Paragraph(p.uploadedAt.format(dateTimeFmt), regular)))
        }
        document.add(registry)

        photos.forEach { p ->
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
}
