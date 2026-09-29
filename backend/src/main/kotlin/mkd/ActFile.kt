package mkd

import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import java.security.MessageDigest
import java.time.Instant

// присланный в чат файл, который ещё не стал актом
data class ActUpload(val bytes: ByteArray, val fileName: String, val receivedAt: Instant, val mid: String) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

// Что делаем с файлом до того, как он станет актом и по нему пойдут сроки 10/30 дней
sealed interface ActFileCheck {
    data object Ok : ActFileCheck

    // адрес дома в акте не нашёлся: УК могла записать его иначе — не блокируем, а переспрашиваем
    data class Confirm(val question: String) : ActFileCheck
    data class Reject(val message: String) : ActFileCheck
}

// Форма акта приёмки одна и закреплена нормативно — приложение к приказу Минстроя от 26.10.2015 № 761/пр,
// поэтому «не та форма» здесь отказ, а не предупреждение. Принимаем только PDF с текстовым слоем:
// в фотографии и в скане-картинке проверить нечем ни форму, ни адрес, а незамеченный чужой акт стоит
// председателю срока в 10 дней
object ActFile {
    const val MAX_BYTES = 20L * 1024 * 1024

    // текст акта уходит в промпт LLM при распознавании — режем, чтобы многостраничный PDF не выел лимит токенов
    const val MAX_TEXT_CHARS = 20_000

    fun check(upload: ActUpload, houseAddress: String, otherAddresses: List<String>): ActFileCheck {
        val text = runCatching { pdfText(upload.bytes) }.getOrNull().orEmpty()
        rejectReason(upload, text, houseAddress, otherAddresses)?.let { return ActFileCheck.Reject(it) }
        return if (addressMatches(text, houseAddress)) ActFileCheck.Ok else ActFileCheck.Confirm(
            "В акте не нашёлся адрес вашего дома ($houseAddress). Возможно, УК записала адрес иначе — " +
                "проверьте, что это акт по вашему дому, и подтвердите загрузку.",
        )
    }

    fun sniffMime(bytes: ByteArray): String? = when {
        looksLikePdf(bytes) -> "application/pdf"
        bytes.startsWith(JPEG_MAGIC) -> "image/jpeg"
        bytes.startsWith(PNG_MAGIC) -> "image/png"
        else -> null
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun pdfText(bytes: ByteArray): String {
        val reader = PdfReader(bytes)
        return try {
            // многостраничный PDF иначе уедет и в разбор текста, и в промпт LLM
            require(reader.numberOfPages in 1..MAX_PAGES) { "страниц: ${reader.numberOfPages}" }
            val extractor = PdfTextExtractor(reader)
            (1..reader.numberOfPages).joinToString("\n") { extractor.getTextFromPage(it) }
        } finally {
            reader.close()
        }
    }

    // сколько якорей формы нашлось — пишем в аудит: без этого непонятно, почему акт отклонён
    fun formScore(text: String): String {
        val squashed = squash(text)
        return "required=${REQUIRED_ANCHORS.count { squash(it) in squashed }}/${REQUIRED_ANCHORS.size}; " +
            "optional=${OPTIONAL_ANCHORS.count { squash(it) in squashed }}/${OPTIONAL_ANCHORS.size}"
    }

    private fun rejectReason(
        upload: ActUpload,
        text: String,
        houseAddress: String,
        otherAddresses: List<String>,
    ): String? = when {
        !upload.fileName.endsWith(".pdf", ignoreCase = true) || !looksLikePdf(upload.bytes) -> NOT_PDF
        text.length < MIN_TEXT_CHARS -> UNREADABLE_PDF
        !matchesForm(text) -> WRONG_FORM
        // адрес чужого дома из справочника — это уже не сомнение, а точно не тот акт
        else -> otherAddresses.firstOrNull { addressMatches(text, it) }?.let { other ->
            "Этот акт составлен по дому $other, а вы председатель дома $houseAddress. " +
                "Загрузить можно только акт по своему дому."
        }
    }

    private fun matchesForm(text: String): Boolean {
        val squashed = squash(text)
        return REQUIRED_ANCHORS.all { squash(it) in squashed } &&
            OPTIONAL_ANCHORS.count { squash(it) in squashed } >= MIN_OPTIONAL_ANCHORS
    }
}

private const val MAX_PAGES = 30
private const val MIN_TEXT_CHARS = 200
private const val MIN_OPTIONAL_ANCHORS = 3
private const val HEADER_SCAN_BYTES = 1024

// Заголовок формы 761/пр. Обязательны оба: по ним акт приёмки отличается от любого другого документа УК
private val REQUIRED_ANCHORS = listOf(
    "приемки оказанных услуг",
    "по содержанию и текущему ремонту общего имущества",
)

// Реквизиты той же формы. Часть может потеряться при конвертации, поэтому нужны не все, а MIN_OPTIONAL_ANCHORS
private val OPTIONAL_ANCHORS = listOf(
    "многоквартирном доме", "761/пр", "заказчик", "исполнитель", "периодичность",
)

// Типы адресных элементов: в справочнике «г. Казань, ул. Демонстрационная, д. 1», в акте может быть
// «Казань, Демонстрационная ул., дом 1» — сравниваем то, что останется после их удаления
private val ADDRESS_NOISE = setOf(
    "г", "гор", "город", "ул", "улица", "пр", "просп", "проспект", "пер", "переулок",
    "б", "бр", "бульвар", "ш", "шоссе", "д", "дом", "корп", "корпус", "к", "стр", "строение",
    "лит", "литера", "мкр", "микрорайон",
)

private const val NOT_PDF =
    "Акт принимается только файлом PDF. Пришлите тот PDF, который прислала УК, — не фото и не скриншот."

private const val UNREADABLE_PDF =
    "Не удалось прочитать текст акта из этого PDF: файл повреждён либо внутри картинка или скан. " +
        "Попросите у УК электронный экземпляр акта."

private const val WRONG_FORM =
    "Это не акт приёмки оказанных услуг и выполненных работ. Акт должен быть по форме приложения " +
        "к приказу Минстроя от 26.10.2015 № 761/пр — другая форма для приёмки работ по содержанию " +
        "дома не применяется. Проверьте, тот ли файл вы отправили."

// сигнатура вместо расширения: имя файла приходит от отправителя и врёт (.pdf с картинкой или архивом внутри).
// По стандарту перед %PDF- допустим мусор, поэтому ищем её в начале файла, а не строго в нулевом байте
private fun looksLikePdf(bytes: ByteArray): Boolean =
    bytes.copyOfRange(0, minOf(bytes.size, HEADER_SCAN_BYTES)).decodeToString().contains("%PDF-")

// В PDF слово рвётся переносом и колонкой («Периодично сть»), поэтому ищем по строке без пробелов и знаков
private fun squash(s: String): String = s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

private fun addressTokens(s: String): List<String> =
    s.lowercase().replace('ё', 'е')
        .split(Regex("[^\\p{L}\\p{Nd}]+"))
        .filter { it.isNotBlank() && it !in ADDRESS_NOISE }

// Границы обязательны с обеих сторон, иначе дом 1 найдётся в акте по дому 11
private fun addressMatches(text: String, address: String): Boolean {
    val needle = addressTokens(address).joinToString(" ")
    if (needle.isBlank()) return false
    val haystack = addressTokens(text).joinToString(" ")
    return Regex("(?<![\\p{L}\\p{Nd}])${Regex.escape(needle)}(?![\\p{L}\\p{Nd}])").containsMatchIn(haystack)
}

@Suppress("MagicNumber")  // сигнатуры форматов — это и есть числа
private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

@Suppress("MagicNumber")
private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)
