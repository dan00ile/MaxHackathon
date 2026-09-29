package mkd

import com.lowagie.text.Document
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Проверка файла до того, как он станет актом: форма 761/пр и адрес дома.
// Эталон — demo/demo-act.pdf, заполненный бланк формы по дому «г. Казань, ул. Демонстрационная, д. 1»
class ActFileTest {
    private val demoAct = File("../demo/demo-act.pdf").readBytes()
    private val house = "г. Казань, ул. Демонстрационная, д. 1"

    private fun check(
        bytes: ByteArray = demoAct,
        fileName: String = "act.pdf",
        address: String = house,
        others: List<String> = emptyList(),
    ) = ActFile.check(ActUpload(bytes, fileName, Instant.EPOCH, "mid"), address, others)

    @Test
    fun `демо-акт по своему дому принимается`() {
        assertEquals(ActFileCheck.Ok, check())
    }

    @Test
    fun `адрес узнаётся в другой записи того же дома`() {
        assertEquals(ActFileCheck.Ok, check(address = "Казань, Демонстрационная улица, дом 1"))
    }

    @Test
    fun `дом 11 не считается домом 1`() {
        // самая коварная ошибка: без границы справа «д. 1» нашлась бы в акте по дому 11
        assertIs<ActFileCheck.Confirm>(check(address = "г. Казань, ул. Демонстрационная, д. 11"))
    }

    @Test
    fun `чужой адрес без справочника только переспрашивает`() {
        assertIs<ActFileCheck.Confirm>(check(address = "г. Казань, ул. Демонстрационная, д. 2"))
    }

    @Test
    fun `акт по дому из справочника отклоняется у председателя другого дома`() {
        val verdict = check(address = "г. Казань, ул. Демонстрационная, д. 2", others = listOf(house))
        assertIs<ActFileCheck.Reject>(verdict)
        assertTrue(verdict.message.contains("д. 1"), verdict.message)
    }

    @Test
    fun `другой документ УК отклоняется как не тот бланк`() {
        // заголовок акта есть, реквизитов формы нет — так выглядит письмо или отказ, а не акт приёмки
        val verdict = check(bytes = pdfOf(NOT_AN_ACT))
        assertIs<ActFileCheck.Reject>(verdict)
        assertTrue(verdict.message.contains("761/пр"), verdict.message)
    }

    @Test
    fun `pdf без текстового слоя отклоняется`() {
        val verdict = check(bytes = pdfOf("Акт"))
        assertIs<ActFileCheck.Reject>(verdict)
        assertTrue(verdict.message.contains("прочитать текст"), verdict.message)
    }

    @Test
    fun `не-pdf под именем акта отклоняется`() {
        assertIs<ActFileCheck.Reject>(check(bytes = pngMagic + ByteArray(1000)))
        assertIs<ActFileCheck.Reject>(check(bytes = "не файл вовсе".toByteArray()))
    }

    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `настоящий pdf под чужим расширением отклоняется`() {
        assertIs<ActFileCheck.Reject>(check(fileName = "act.jpg"))
    }

    @Test
    fun `тип файла определяется по содержимому`() {
        assertEquals("application/pdf", ActFile.sniffMime(demoAct))
        assertEquals("image/png", ActFile.sniffMime(pngMagic))
        assertEquals("image/jpeg", ActFile.sniffMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals(null, ActFile.sniffMime("MZ исполняемый файл".toByteArray()))
    }

    private fun pdfOf(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        val document = Document()
        PdfWriter.getInstance(document, out)
        document.open()
        document.add(Paragraph(text, Pdf.font("DejaVuSans.ttf", 11f)))
        document.close()
        return out.toByteArray()
    }
}

private const val NOT_AN_ACT =
    "Уведомление о приемки оказанных услуг по содержанию и текущему ремонту общего имущества. " +
        "Настоящим сообщаем, что работы за отчетный период завершены в полном объеме и в установленные " +
        "договором сроки. Сведения о стоимости и объемах будут направлены отдельным письмом после сверки " +
        "взаиморасчетов между сторонами договора управления."
