package mkd

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.jetbrains.exposed.sql.ResultRow

// Тексты и кнопки сообщений бота: статус акта, приветствие, отсчёт сроков

private val statusRu = mapOf(
    ActStatus.RECEIVED to "Получен",
    ActStatus.COLLECTING to "Идёт сбор замечаний",
    ActStatus.REVIEW to "Решение председателя",
    ActStatus.SIGNED to "Подписан",
    ActStatus.REJECTED to "Отказ направлен",
    ActStatus.SILENT to "Принят молчаливым согласием",
)

fun statusText(act: ResultRow, address: String, now: Instant, zone: ZoneId, eventAt: Instant? = null): String {
    val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    val status = act[Acts.status]
    val header = "Акт № ${act[Acts.number] ?: "без номера"} за ${act[Acts.period] ?: "—"}, $address\n" +
            "Статус: ${statusRu[status]}"
    if (status !in ACTIVE_STATUSES) {
        return if (eventAt != null) "$header\nДата: ${fmt.format(eventAt.atZone(zone).toLocalDate())}" else header
    }
    val d10 = act[Acts.deadline10]
    val d30 = act[Acts.deadline30]
    val n10 = Deadlines.daysLeft(d10, now, zone)
    val n30 = Deadlines.daysLeft(d30, now, zone)
    val line10 = if (n10 < 0) {
        "Срок по приказу истёк ${fmt.format(d10)}, но акт ещё не считается принятым — решение можно принять до ${
            fmt.format(
                d30
            )
        }."
    } else {
        "Срок по приказу (10 дней): до ${fmt.format(d10)} — осталось дней: $n10"
    }
    val line30 = "Защитный срок (30 дней): до ${fmt.format(d30)} — осталось дней: $n30"
    return "$header\n$line10\n$line30"
}

// Приветствие/меню: если по дому есть акт, сразу показываем его статус. Иначе в меню висела бы
// ссылка «Открыть акт» без указания, какой именно акт откроется, — она бы осталась в чате и после
// того, как дом перешёл к следующему акту (ссылка-диплинк держит id навсегда)
fun menuText(status: String?, isChairman: Boolean): String {
    val head =
        if (isChairman) "Акт присылайте сюда файлом — PDF или фото."
        else "Отслеживайте здесь проверку акта работ по вашему дому."
    return if (status == null) head else head + "\n\n" + status
}

private val updatedFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy 'в' HH:mm")

// приписка к статусу, который по кнопке «Обновить статус» заменил собой прежний
fun updatedNote(at: Instant, zone: ZoneId): String = "\n\nОбновлено ${updatedFormat.format(at.atZone(zone))}"

fun statusButtons(act: ResultRow, isChairman: Boolean): List<List<Button>> {
    val status = act[Acts.status]
    val actId = act[Acts.id].value
    val buttons = mutableListOf<List<Button>>()
    // ссылка есть при любом статусе: мини-апп умеет показывать и завершённый акт (итоговый баннер)
    buttons.add(listOf(link("Открыть акт", appLink("act_$actId"))))
    if (isChairman && status == ActStatus.COLLECTING) buttons.add(
        listOf(
            cb(
                "Завершить сбор замечаний",
                "close:$actId"
            )
        )
    )
    if (isChairman && (status == ActStatus.COLLECTING || status == ActStatus.REVIEW)) {
        buttons.add(listOf(cb("Подписать", "sign:$actId")))
        buttons.add(listOf(cb("Сформировать отказ", "refuse:$actId")))
    }
    buttons.add(listOf(cb("Обновить статус", "status")))
    return buttons
}
