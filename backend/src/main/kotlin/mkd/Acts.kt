package mkd

import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable data class RecognizedItem(
    val lineNo: Int = 0, val name: String = "", val periodicity: String = "",
    val volume: String = "", val cost: String = "", val workKind: String = "OTHER",
)
@Serializable data class RecognizedAct(
    val number: String = "", val formedDate: String = "", val period: String = "",
    val items: List<RecognizedItem> = emptyList(),
)

suspend fun itemActId(itemId: Long): Long = tx {
    ActItems.select(ActItems.actId).where { ActItems.id eq itemId }.singleOrNull()
        ?.get(ActItems.actId)?.value ?: throw ApiError(HttpStatusCode.NotFound, "not_found", "Позиция не найдена")
}

object Deadlines {
    val MILESTONES = listOf(0, 7, 10, 25, 28, 29)

    fun day10(receivedAt: Instant, zone: ZoneId): LocalDate = receivedAt.atZone(zone).toLocalDate().plusDays(10)
    fun day30(receivedAt: Instant, zone: ZoneId): LocalDate = receivedAt.atZone(zone).toLocalDate().plusDays(30)

    // молчаливое согласие наступает в начале дня, следующего за T0+30
    fun silentAt(deadline30: LocalDate, zone: ZoneId): Instant = deadline30.plusDays(1).atStartOfDay(zone).toInstant()

    // момент уведомления: d=0 — сразу; иначе 10:00 местного времени в день T0+d
    fun milestoneAt(receivedAt: Instant, d: Int, zone: ZoneId): Instant =
        if (d == 0) receivedAt
        else receivedAt.atZone(zone).toLocalDate().plusDays(d.toLong()).atTime(10, 0).atZone(zone).toInstant()

    // сколько календарных дней осталось до конца дня `until` (0 = сегодня последний день, <0 = прошёл)
    fun daysLeft(until: LocalDate, now: Instant, zone: ZoneId): Long =
        ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), until)
}

class ActService(
    private val cfg: Config, private val max: MaxBotClient, private val gigaChat: GigaChatClient, private val timers: TimerService,
) {

    suspend fun activeAct(houseId: Long): ResultRow? = tx {
        Acts.selectAll()
            .where { (Acts.houseId eq houseId) and (Acts.status inList listOf(ActStatus.RECEIVED, ActStatus.COLLECTING, ActStatus.REVIEW)) }
            .orderBy(Acts.createdAt to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
    }

    // последний акт дома независимо от статуса — запасной вариант для /status, когда активного акта уже нет
    suspend fun lastAct(houseId: Long): ResultRow? = tx {
        Acts.selectAll().where { Acts.houseId eq houseId }.orderBy(Acts.createdAt to SortOrder.DESC).limit(1).singleOrNull()
    }

    suspend fun terminalEventAt(actId: Long, status: ActStatus): Instant? {
        val type = when (status) {
            ActStatus.SIGNED -> "SIGNED"
            ActStatus.REJECTED -> "REFUSAL_SENT"
            ActStatus.SILENT -> "SILENT_CONSENT"
            else -> return null
        }
        return tx {
            Events.selectAll().where { (Events.actId eq actId) and (Events.type eq type) }
                .orderBy(Events.at to SortOrder.DESC).limit(1).singleOrNull()?.get(Events.at)
        }
    }

    suspend fun createFromUpload(
        userId: Long, houseId: Long, bytes: ByteArray, fileName: String, mime: String,
        receivedAt: Instant, mid: String,
    ): Long {
        val ext = when (mime) {
            "application/pdf" -> "pdf"
            "image/png" -> "png"
            else -> "jpg"
        }
        val dir = Path.of(cfg.filesDir, "acts")
        Files.createDirectories(dir)
        val storedPath = dir.resolve("${UUID.randomUUID()}.$ext")
        Files.write(storedPath, bytes)

        val deadline10 = Deadlines.day10(receivedAt, cfg.zone)
        val deadline30 = Deadlines.day30(receivedAt, cfg.zone)
        return tx {
            val id = Acts.insert {
                it[Acts.houseId] = EntityID(houseId, Houses)
                it[Acts.receivedAt] = receivedAt
                it[Acts.deadline10] = deadline10
                it[Acts.deadline30] = deadline30
                it[Acts.status] = ActStatus.RECEIVED
                it[Acts.filePath] = storedPath.toString()
                it[Acts.fileName] = fileName
                it[Acts.uploadedBy] = userId
                it[Acts.recognition] = Recognition.PENDING
                it[Acts.createdAt] = Instant.now()
            } get Acts.id
            logEvent(id.value, "ACT_RECEIVED", userId, "mid=$mid; uploadedAt=${Instant.now()}; file=$fileName")
            id.value
        }
    }

    suspend fun setReceiptDate(actId: Long, userId: Long, date: LocalDate) {
        val zone = cfg.zone
        val today = Instant.now().atZone(zone).toLocalDate()
        if (date.isAfter(today) || date.isBefore(today.minusDays(30))) {
            throw ApiError(HttpStatusCode.BadRequest, "invalid_date", "Дата должна быть не позже сегодняшней и не раньше чем 30 дней назад")
        }
        val receivedAt = date.atTime(12, 0).atZone(zone).toInstant()
        val deadline10 = Deadlines.day10(receivedAt, zone)
        val deadline30 = Deadlines.day30(receivedAt, zone)
        tx {
            Acts.update({ Acts.id eq actId }) {
                it[Acts.receivedAt] = receivedAt
                it[Acts.deadline10] = deadline10
                it[Acts.deadline30] = deadline30
            }
            logEvent(actId, "RECEIPT_DATE_SET", userId, "date=$date")
        }
    }

    suspend fun requireChairmanOf(actId: Long, userId: Long): ResultRow = tx {
        val act = Acts.selectAll().where { Acts.id eq actId }.singleOrNull()
            ?: throw ApiError(HttpStatusCode.NotFound, "not_found", "Акт не найден")
        val houseId = act[Acts.houseId].value
        val confirmed = Chairmen.selectAll()
            .where { (Chairmen.userId eq userId) and (Chairmen.houseId eq houseId) }
            .singleOrNull()?.get(Chairmen.confirmedAt) != null
        if (!confirmed) throw ApiError(HttpStatusCode.Forbidden, "forbidden", "Доступно только председателю")
        act
    }

    suspend fun demoShift(actId: Long, userId: Long, days: Int) {
        if (!cfg.demoMode) throw ApiError(HttpStatusCode.Forbidden, "not_demo", "Команда доступна только в демо-режиме")
        requireChairmanOf(actId, userId)
        if (days !in 1..40) throw ApiError(HttpStatusCode.BadRequest, "invalid_days", "Число дней должно быть от 1 до 40")
        tx {
            val act = Acts.selectAll().where { Acts.id eq actId }.single()
            val receivedAt = act[Acts.receivedAt].minus(days.toLong(), ChronoUnit.DAYS)
            Acts.update({ Acts.id eq actId }) {
                it[Acts.receivedAt] = receivedAt
                it[Acts.deadline10] = Deadlines.day10(receivedAt, cfg.zone)
                it[Acts.deadline30] = Deadlines.day30(receivedAt, cfg.zone)
            }
            logEvent(actId, "DEMO_SHIFT", userId, "days=$days")
        }
        timers.tick()
    }

    suspend fun requireMemberOf(actId: Long, userId: Long): ResultRow = tx {
        val act = Acts.selectAll().where { Acts.id eq actId }.singleOrNull()
            ?: throw ApiError(HttpStatusCode.NotFound, "not_found", "Акт не найден")
        val houseId = act[Acts.houseId].value
        val user = Users.selectAll().where { Users.id eq userId }.singleOrNull()
        if (user == null || user[Users.houseId]?.value != houseId) {
            throw ApiError(HttpStatusCode.Forbidden, "forbidden", "Доступно только жителям этого дома")
        }
        act
    }

    suspend fun closeCollection(actId: Long, userId: Long) {
        requireChairmanOf(actId, userId)
        tx {
            val act = Acts.selectAll().where { Acts.id eq actId }.single()
            if (act[Acts.status] != ActStatus.COLLECTING) {
                throw ApiError(HttpStatusCode.Conflict, "not_collecting", "Сбор замечаний уже завершён")
            }
            Acts.update({ Acts.id eq actId }) { it[status] = ActStatus.REVIEW }
            ActItems.selectAll().where { (ActItems.actId eq actId) and (ActItems.decision.isNull()) }.forEach { item ->
                val itemId = item[ActItems.id].value
                val issueWithPhoto = Remarks.selectAll().where { Remarks.itemId eq itemId }
                    .count { it[Remarks.verdict] == Verdict.ISSUE && photosOfTx(it[Remarks.id].value).isNotEmpty() }
                ActItems.update({ ActItems.id eq itemId }) {
                    it[decision] = if (issueWithPhoto > 0) Decision.DISPUTE else Decision.ACCEPT
                }
            }
            logEvent(actId, "COLLECTION_CLOSED", userId)
        }
    }

    suspend fun setDecision(itemId: Long, userId: Long, decision: Decision) {
        val actId = itemActId(itemId)
        requireChairmanOf(actId, userId)
        tx {
            val status = Acts.select(Acts.status).where { Acts.id eq actId }.single()[Acts.status]
            if (status != ActStatus.COLLECTING && status != ActStatus.REVIEW) {
                throw ApiError(HttpStatusCode.Conflict, "wrong_status", "Решение можно менять только во время сбора замечаний или на этапе решения")
            }
            if (decision == Decision.DISPUTE) {
                val issueWithPhoto = Remarks.selectAll().where { Remarks.itemId eq itemId }
                    .count { it[Remarks.verdict] == Verdict.ISSUE && photosOfTx(it[Remarks.id].value).isNotEmpty() }
                if (issueWithPhoto == 0) {
                    throw ApiError(HttpStatusCode.Conflict, "no_evidence", "По позиции нет замечаний с фото — оснований для возражения недостаточно")
                }
            }
            ActItems.update({ ActItems.id eq itemId }) { it[ActItems.decision] = decision }
            logEvent(actId, "DECISION_SET", userId, "itemId=$itemId; decision=$decision")
        }
    }

    suspend fun recognize(actId: Long) {
        val act = tx { Acts.selectAll().where { Acts.id eq actId }.single() }
        val fileName = act[Acts.fileName]
        val houseId = act[Acts.houseId].value
        val validKinds = tx { Grounds.selectAll().map { it[Grounds.workKind] }.toSet() }

        val recognized = runCatching {
            check(gigaChat.enabled) { "GigaChat не настроен" }
            val bytes = Files.readAllBytes(Path.of(act[Acts.filePath]))
            val mime = mimeOfFileName(fileName)
            val content = if (mime == "application/pdf") {
                val text = extractPdfText(bytes)
                if (text.length < 50) recognizeFromImage(bytes, fileName, mime, validKinds)
                else gigaChat.chat(systemRecognizePrompt(validKinds), "Текст акта:\n$text")
            } else {
                recognizeFromImage(bytes, fileName, mime, validKinds)
            }
            parseRecognized(content, validKinds)
        }

        recognized.onSuccess { r ->
            tx {
                Acts.update({ Acts.id eq actId }) {
                    it[number] = r.number.ifBlank { null }
                    it[period] = r.period.ifBlank { null }
                    it[formedDate] = runCatching { LocalDate.parse(r.formedDate) }.getOrNull()
                    it[recognition] = Recognition.DONE
                }
                r.items.forEachIndexed { idx, item ->
                    ActItems.insert {
                        it[ActItems.actId] = EntityID(actId, Acts)
                        it[lineNo] = if (item.lineNo > 0) item.lineNo else idx + 1
                        it[name] = item.name
                        it[periodicity] = item.periodicity
                        it[volume] = item.volume
                        it[cost] = item.cost
                        it[workKind] = item.workKind
                    }
                }
                logEvent(actId, "RECOGNIZED", null, "items=${r.items.size}")
            }
            notifyChairmen(houseId, actId, "Распознано позиций: ${r.items.size}. Проверьте их и откройте сбор замечаний жителей.")
        }.onFailure {
            tx {
                Acts.update({ Acts.id eq actId }) { it[recognition] = Recognition.FAILED }
                logEvent(actId, "RECOGNITION_FAILED", null, it.message ?: "")
            }
            notifyChairmen(houseId, actId, "Не удалось распознать акт автоматически — введите позиции вручную, это займёт пару минут.")
        }
    }

    private suspend fun recognizeFromImage(bytes: ByteArray, fileName: String, mime: String, validKinds: Set<String>): String {
        val fileId = gigaChat.uploadFile(bytes, fileName, mime)
        return gigaChat.chat(systemRecognizePrompt(validKinds), "Распознай акт на изображении.", listOf(fileId))
    }

    private suspend fun notifyChairmen(houseId: Long, actId: Long, text: String) {
        val chairmen = tx {
            Chairmen.selectAll()
                .where { (Chairmen.houseId eq houseId) and (Chairmen.confirmedAt.isNotNull()) }
                .map { it[Chairmen.userId] }
        }
        chairmen.forEach { uid -> max.sendText(uid, text, listOf(listOf(link("Открыть акт", appLink("act_$actId"))))) }
    }
}

private val ACTIVE_STATUSES = setOf(ActStatus.RECEIVED, ActStatus.COLLECTING, ActStatus.REVIEW)

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
        "Срок по приказу истёк ${fmt.format(d10)}, но акт ещё не считается принятым — решение можно принять до ${fmt.format(d30)}."
    } else {
        "Срок по приказу (10 дней): до ${fmt.format(d10)} — осталось дней: $n10"
    }
    val line30 = "Защитный срок (30 дней): до ${fmt.format(d30)} — осталось дней: $n30"
    return "$header\n$line10\n$line30"
}

fun statusButtons(cfg: Config, act: ResultRow, isChairman: Boolean, isResident: Boolean): List<List<Button>> {
    val status = act[Acts.status]
    val actId = act[Acts.id].value
    val buttons = mutableListOf<List<Button>>()
    if (status in ACTIVE_STATUSES) buttons.add(listOf(link("Открыть акт", appLink("act_$actId"))))
    if (isChairman && status == ActStatus.COLLECTING) buttons.add(listOf(cb("Завершить сбор замечаний", "close:$actId")))
    if (isChairman && (status == ActStatus.COLLECTING || status == ActStatus.REVIEW)) {
        buttons.add(listOf(cb("Подписать", "sign:$actId")))
        buttons.add(listOf(cb("Сформировать отказ", "refuse:$actId")))
    }
    return buttons
}

private fun mimeOfFileName(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    else -> "image/jpeg"
}

private fun extractPdfText(bytes: ByteArray): String {
    val reader = PdfReader(bytes)
    return try {
        val extractor = PdfTextExtractor(reader)
        (1..reader.numberOfPages).joinToString("\n") { extractor.getTextFromPage(it) }
    } finally {
        reader.close()
    }
}

private fun systemRecognizePrompt(validKinds: Set<String>): String = """
    Ты извлекаешь данные из акта приёмки оказанных услуг и выполненных работ по содержанию и
    текущему ремонту общего имущества МКД (форма приказа Минстроя № 761/пр).
    Верни ТОЛЬКО JSON без пояснений в формате:
    {"number":"...","formedDate":"YYYY-MM-DD","period":"...","items":[{"lineNo":1,"name":"...",
    "periodicity":"...","volume":"...","cost":"...","workKind":"..."}]}
    workKind — один код из списка: ${validKinds.joinToString(", ")}.
    Если поля нет в акте — пустая строка. Ничего не придумывай.
""".trimIndent()

private fun parseRecognized(content: String, validKinds: Set<String>): RecognizedAct {
    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    check(start >= 0 && end > start) { "LLM не вернул JSON" }
    val raw = AppJson.decodeFromString<RecognizedAct>(content.substring(start, end + 1))
    return raw.copy(items = raw.items.map { if (it.workKind !in validKinds) it.copy(workKind = "OTHER") else it })
}
