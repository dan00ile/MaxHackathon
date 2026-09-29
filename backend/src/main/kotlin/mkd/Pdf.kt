package mkd

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.Image
import com.lowagie.text.PageSize
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

data class PhotoPage(
    val registryNo: Int, val lineNo: Int, val itemName: String, val author: String,
    val uploadedAt: ZonedDateTime, val file: File,
)

data class RefusalPdfData(
    val houseAddress: String, val ukName: String, val ukAddress: String,
    val ukRepresentative: String, val exchangeMethod: String,
    val actNumber: String?, val formedDate: LocalDate?, val period: String?,
    val objections: List<Objection>, val noObjectionLineNos: List<Int>,
    val photos: List<PhotoPage>,
    val chairmanFio: String, val place: String, val composedAt: ZonedDateTime, val demo: Boolean,
)

private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

private const val ORDER_318 = "приказом Минстроя России от 22.05.2026 № 318/пр"

// Пункт Порядка, по которому УК оформляет новый акт ({{ПУНКТ_ПОРЯДКА_НОВОГО_АКТА}} в шаблоне)
private const val NEW_ACT_CLAUSE = "6"

// Times New Roman в репозиторий положить нельзя (проприетарный шрифт Monotype, а деплой — Linux-контейнер
// без MS-шрифтов), поэтому встроен Tinos: метрически идентичный клон TNR под OFL. Проверено — у всех
// используемых в документе символов ширины совпадают с times.ttf/timesbd.ttf до единицы, значит переносы
// строк и вид текста те же, что в шаблоне.
private const val TIMES = "Tinos-Regular.ttf"
private const val TIMES_BOLD = "Tinos-Bold.ttf"

// Геометрия снята с docs/Шаблон_мотивированного_отказа.pdf: A4, поля в один дюйм, основной текст 10.08 pt,
// заголовок 13.92 pt полужирный. Интерлиньяж и отбивки — измеренные расстояния между базовыми линиями.
private const val MARGIN = 72f

// Верхнее поле подобрано, а не взято равным MARGIN: OpenPDF опускает первую строку на полный интерлиньяж
// от поля, а Word (в котором сделан шаблон) — на высоту прописной. Без поправки весь текст уезжает на
// 4.3 pt вниз относительно шаблона. 842 − 67.76 − HEADER_LEADING = 760.8 — базовая линия как в шаблоне.
private const val MARGIN_TOP = 67.76f
private const val BODY_SIZE = 10.08f
private const val TITLE_SIZE = 13.92f
private const val BODY_LEADING = 11.52f
private const val HEADER_LEADING = 13.44f
private const val TITLE_LEADING = 16.08f
private const val TITLE_GAP = 14.43f      // от последней строки шапки до заголовка
private const val TITLE_TO_BODY = 15.84f
private const val PARA_GAP = 10.08f       // пустая строка между абзацами
private const val BULLET_GAP = 2.88f      // между строками внутри блока позиции
private const val BULLET_INDENT = 19.92f  // отбивка блока позиции от левого поля
// Заголовок в шаблоне сужен относительно основного текста, из-за этого он рвётся после «оказанных»,
// а не после «услуг». Ширина колонки заголовка в шаблоне — между 413 и 449 pt, берём середину
private const val TITLE_INDENT = 14f
private const val SIGN_GAP = 18.26f       // от текста до подписной части

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

    // Документ повторяет docs/Шаблон_мотивированного_отказа.pdf и по тексту, и по виду: шапка «кому/от кого»,
    // вводный абзац по п. 4 Порядка, блок на каждую спорную позицию, просьба оформить новый акт, подпись.
    // Формулировки шаблона править только вместе с самим шаблоном — RefusalTemplateConformanceTest читает
    // его файл и сверяет постоянный текст. Сверх шаблона — обязательные реквизиты FR-G2: уведомление УК
    // с её представителем, место составления, реестр фото и страницы-приложения (FR-G2.1)
    fun refusal(d: RefusalPdfData): ByteArray {
        val regular = font(TIMES, BODY_SIZE)
        val labelFont = font(TIMES_BOLD, BODY_SIZE)
        val bold = font(TIMES_BOLD, TITLE_SIZE)
        val small = font(TIMES, 9f)

        val out = ByteArrayOutputStream()
        val document = Document(PageSize.A4, MARGIN, MARGIN, MARGIN_TOP, MARGIN)
        PdfWriter.getInstance(document, out)
        document.open()
        fun para(
            text: String,
            font: Font = regular,
            align: Int = Element.ALIGN_JUSTIFIED,
            before: Float = 0f,
            leading: Float = BODY_LEADING,
            indent: Float = 0f,
            indentRight: Float = 0f,
        ) = document.add(
            Paragraph(text, font).apply {
                alignment = align
                spacingBefore = before
                indentationLeft = indent
                indentationRight = indentRight
                setLeading(leading, 0f)
            },
        )

        // Шапка «кому / от кого» — четыре строки по правому краю, как в шаблоне
        listOfNotNull(
            "В ${d.ukName}",
            d.ukAddress.ifBlank { null },
            "от председателя совета многоквартирного дома",
            "${d.chairmanFio}, ${d.houseAddress}",
        ).forEach { para(it, align = Element.ALIGN_RIGHT, leading = HEADER_LEADING) }

        para(
            "Мотивированный отказ от подписания акта приёмки оказанных услуг (выполненных работ)",
            bold, Element.ALIGN_CENTER, before = TITLE_GAP, leading = TITLE_LEADING,
            indent = TITLE_INDENT, indentRight = TITLE_INDENT,
        )
        val actDate = d.formedDate?.let { " от ${it.format(dateFmt)}" } ?: ""
        para(
            "В соответствии с пунктом 4 Порядка, утверждённого $ORDER_318, сообщаю об отказе от подписания " +
                "акта приёмки оказанных услуг (выполненных работ) № ${d.actNumber ?: "без номера"}$actDate " +
                "по содержанию и текущему ремонту общего имущества многоквартирного дома по адресу: " +
                "${d.houseAddress}, ${periodPhrase(d.period)}.",
            before = TITLE_TO_BODY,
        )
        para(
            "Отказ от подписания акта мотивирован следующими аргументированными возражениями против его содержания, " +
                "установленными на основании опроса собственников и пользователей помещений многоквартирного дома:",
            before = PARA_GAP,
        )

        d.objections.forEach { o ->
            para("${o.lineNo}. ${o.itemName}", labelFont, Element.ALIGN_LEFT, before = PARA_GAP)
            val bullet = { text: String -> para(text, before = BULLET_GAP, indent = BULLET_INDENT) }
            bullet("Указано в акте: ${sentence(o.actWording.ifBlank { "«${o.itemName}»" })}")
            bullet(
                "По данным опроса жильцов: ${o.fact.trimEnd().trimEnd('.')} (отметили выполнение — " +
                    "${o.okCount} из ${o.okCount + o.issueCount} опрошенных).",
            )
            val appendixNos = d.photos.filter { it.lineNo == o.lineNo }.map { it.registryNo }
            bullet(
                "Приложены фотоматериалы: " +
                    if (appendixNos.isEmpty()) "нет." else "приложения № ${appendixNos.joinToString(", ")}.",
            )
            bullet("Возражение: ${sentence(o.demand)}")
        }

        if (d.noObjectionLineNos.isNotEmpty()) {
            para("По позициям № ${d.noObjectionLineNos.joinToString(", ")} возражений не имеется.", before = PARA_GAP)
        }
        // сверх шаблона, но обязательно по FR-G2: представитель УК и факт уведомления об отказе.
        // Стоит до просительной части, чтобы в шаблонном порядке за ней сразу шла подписная часть
        para(
            "Настоящий отказ направляется исполнителю (${d.ukRepresentative}): ${d.exchangeMethod}.",
            before = PARA_GAP,
        )
        para(
            "На основании изложенного прошу устранить указанные замечания и оформить новый акт приёмки в порядке, " +
                "установленном пунктом $NEW_ACT_CLAUSE Порядка, утверждённого $ORDER_318.",
            before = PARA_GAP,
        )

        val sign = { text: String, before: Float ->
            para(text, align = Element.ALIGN_LEFT, before = before, leading = HEADER_LEADING)
        }
        sign("Дата: ${d.composedAt.format(dateFmt)}", SIGN_GAP)
        sign("Место составления: ${d.place}", 0f)
        sign("Председатель совета МКД: ${d.chairmanFio}", 0f)
        sign("Подпись: _______________", 0f)

        if (d.demo) {
            para(
                "Демо-режим: документ сформирован без квалифицированной электронной подписи (Госключ не подключён). " +
                    "Сведения об УК и справочник оснований — демонстрационные.",
                small, Element.ALIGN_LEFT, before = PARA_GAP, leading = 10.5f,
            )
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
