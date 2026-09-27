package mkd

import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*

@Serializable
data class RecognizedItem(
    val lineNo: Int = 0, val name: String = "", val periodicity: String = "",
    val volume: String = "", val cost: String = "", val workKind: String = "OTHER",
)

@Serializable
data class RecognizedAct(
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
    private val cfg: Config,
    private val max: MaxBotClient,
    private val gigaChat: GigaChatClient,
    private val timers: TimerService,
) {

    suspend fun activeAct(houseId: Long): ResultRow? = tx { activeActTx(houseId) }

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

    // «Другая дата» — до ввода даты: не спрашиваем её у акта, где T0 уже зафиксирован
    suspend fun requireReceiptOpen(actId: Long, userId: Long) {
        requireChairmanOf(actId, userId)
        tx { lockOpenReceiptTx(actId, cfg.zone) }
    }

    // date == null — «Сегодня»: T0 остаётся моментом загрузки акта
    suspend fun confirmReceipt(actId: Long, userId: Long, date: LocalDate?): ResultRow {
        requireChairmanOf(actId, userId)
        val zone = cfg.zone
        if (date != null) checkReceiptDate(date, Instant.now().atZone(zone).toLocalDate())
        return tx { confirmReceiptTx(actId, userId, date?.atTime(12, 0)?.atZone(zone)?.toInstant(), zone) }
    }

    // акт с истёкшим сроком, от которого председатель отказался: дата получения так и не подтверждена.
    // В архиве его не видят ни таймеры, ни списки актов
    suspend fun archiveUnconfirmed(actId: Long, userId: Long) {
        requireChairmanOf(actId, userId)
        tx {
            lockOpenReceiptTx(actId, cfg.zone)
            // повторное нажатие кнопки не пишет второе событие
            val archived = Acts.update({ (Acts.id eq actId) and Acts.archivedAt.isNull() }) {
                it[archivedAt] = Instant.now()
            }
            if (archived > 0) logEvent(actId, "ACT_ARCHIVED", userId, "reason=receipt_expired")
        }
    }

    suspend fun requireChairmanOf(actId: Long, userId: Long): ResultRow = tx {
        val act = Acts.selectAll().where { Acts.id eq actId }.singleOrNull()
            ?: throw ApiError(HttpStatusCode.NotFound, "not_found", "Акт не найден")
        if (act[Acts.archivedAt] != null) {
            throw ApiError(HttpStatusCode.NotFound, "not_found", "Акт не найден")
        }
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
        if (days !in 1..40) throw ApiError(
            HttpStatusCode.BadRequest,
            "invalid_days",
            "Число дней должно быть от 1 до 40"
        )
        tx {
            val act = Acts.selectAll().where { Acts.id eq actId }.single()
            if (act[Acts.status] == ActStatus.REJECTED) {
                // у акта с отказом сроки 10/30 уже не идут — сдвигаем отправку отказа (напоминание о новом акте)
                val refusal = Refusals.selectAll().where { Refusals.actId eq actId }.single()
                Refusals.update({ Refusals.actId eq actId }) {
                    it[Refusals.sentAt] = refusal[Refusals.sentAt]!!.minus(days.toLong(), ChronoUnit.DAYS)
                }
                logEvent(actId, "DEMO_SHIFT", userId, "days=$days refusal")
                return@tx
            }
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
        if (act[Acts.archivedAt] != null) {
            throw ApiError(HttpStatusCode.NotFound, "not_found", "Акт не найден")
        }
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
                throw ApiError(
                    HttpStatusCode.Conflict,
                    "wrong_status",
                    "Решение можно менять только во время сбора замечаний или на этапе решения"
                )
            }
            if (decision == Decision.DISPUTE) {
                val issueWithPhoto = Remarks.selectAll().where { Remarks.itemId eq itemId }
                    .count { it[Remarks.verdict] == Verdict.ISSUE && photosOfTx(it[Remarks.id].value).isNotEmpty() }
                if (issueWithPhoto == 0) {
                    throw ApiError(
                        HttpStatusCode.Conflict,
                        "no_evidence",
                        "По позиции нет замечаний с фото — оснований для возражения недостаточно"
                    )
                }
            }
            ActItems.update({ ActItems.id eq itemId }) { it[ActItems.decision] = decision }
            logEvent(actId, "DECISION_SET", userId, "itemId=$itemId; decision=$decision")
        }
    }

    suspend fun sign(actId: Long, userId: Long) {
        requireChairmanOf(actId, userId)
        val act = tx { Acts.selectAll().where { Acts.id eq actId }.single() }
        if (act[Acts.status] != ActStatus.COLLECTING && act[Acts.status] != ActStatus.REVIEW) {
            throw ApiError(
                HttpStatusCode.Conflict,
                "wrong_status",
                "Акт уже подписан, направлен отказ или принят молчаливым согласием"
            )
        }
        if (act[Acts.status] == ActStatus.COLLECTING) closeCollection(actId, userId)

        val houseId = act[Acts.houseId].value
        val house = tx { Houses.selectAll().where { Houses.id eq houseId }.single() }
        val uk =
            tx { ManagementCompanies.selectAll().where { ManagementCompanies.id eq house[Houses.ukId].value }.single() }
        val chairman =
            tx { Chairmen.selectAll().where { (Chairmen.userId eq userId) and (Chairmen.houseId eq houseId) }.single() }
        val items = tx {
            ActItems.selectAll().where { ActItems.actId eq actId }.orderBy(ActItems.lineNo to SortOrder.ASC).toList()
        }

        val signedAt = ZonedDateTime.now(cfg.zone)
        val bytes = Pdf.signedAct(
            SignedActData(
                houseAddress = house[Houses.address],
                ukName = uk[ManagementCompanies.name],
                actNumber = act[Acts.number],
                formedDate = act[Acts.formedDate],
                period = act[Acts.period],
                items = items.map {
                    ItemRow(
                        it[ActItems.lineNo],
                        it[ActItems.name],
                        it[ActItems.periodicity],
                        it[ActItems.volume],
                        it[ActItems.cost]
                    )
                },
                chairmanFio = chairman[Chairmen.fullName],
                signedAt = signedAt,
                demo = cfg.demoMode,
            ),
        )
        val dir = Path.of(cfg.filesDir, "pdf")
        Files.createDirectories(dir)
        val pdfPath = dir.resolve("act-$actId-signed.pdf")
        Files.write(pdfPath, bytes)

        tx {
            Acts.update({ Acts.id eq actId }) {
                it[signedPdfPath] = pdfPath.toString()
                it[status] = ActStatus.SIGNED
            }
            logEvent(actId, "SIGNED", userId, "stub=true")
        }

        val number = act[Acts.number] ?: "без номера"
        max.sendFile(
            userId, bytes, "akt-$number-podpisan.pdf",
            "Акт подписан. Перешлите этот файл в УК (${uk[ManagementCompanies.exchangeMethod]}) — это ваш подписанный экземпляр.",
        )
        val residents =
            tx { Users.selectAll().where { (Users.houseId eq houseId) and (Users.id neq userId) }.map { it[Users.id] } }
        residents.forEach { uid ->
            runCatching {
                max.sendText(
                    uid,
                    "Председатель подписал акт № $number без возражений."
                )
            }
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
            notifyChairmen(
                houseId,
                actId,
                "Распознано позиций: ${r.items.size}. Проверьте их и откройте сбор замечаний жителей."
            )
        }.onFailure {
            tx {
                Acts.update({ Acts.id eq actId }) { it[recognition] = Recognition.FAILED }
                logEvent(actId, "RECOGNITION_FAILED", null, it.message ?: "")
            }
            notifyChairmen(
                houseId,
                actId,
                "Не удалось распознать акт автоматически — введите позиции вручную, это займёт пару минут."
            )
        }
    }

    private suspend fun recognizeFromImage(
        bytes: ByteArray,
        fileName: String,
        mime: String,
        validKinds: Set<String>
    ): String {
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

// T0 подтверждается один раз: от него идут сроки 10/30 дней, а кнопки старого сообщения с вопросом о дате
// остаются кликабельными. Вызывать только внутри tx { }: строка акта блокируется до конца транзакции,
// поэтому два быстрых нажатия не подтвердят дату дважды. Статус не RECEIVED — акт уже в работе
// (и акты, заведённые до события RECEIPT_CONFIRMED)
fun lockOpenReceiptTx(actId: Long, zone: ZoneId): ResultRow {
    val act = Acts.selectAll().where { Acts.id eq actId }.forUpdate().single()
    val confirmed = act[Acts.status] != ActStatus.RECEIVED ||
            Events.selectAll().where { (Events.actId eq actId) and (Events.type eq "RECEIPT_CONFIRMED") }.count() > 0
    if (confirmed) {
        val date = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(zone).format(act[Acts.receivedAt])
        throw ApiError(
            HttpStatusCode.Conflict, "receipt_confirmed",
            "Дата получения этого акта уже зафиксирована — $date. Изменить её нельзя: от неё идут сроки " +
                    "10 и 30 дней. Если пришёл новый акт — загрузите его файлом.",
        )
    }
    return act
}

private const val RECEIPT_WINDOW_DAYS = 30L

// дата получения акта, введённая председателем: не позже сегодня и не раньше 30 дней назад.
// Текст ошибки бот шлёт как есть и ждёт следующую дату, поэтому в нём — просьба ввести её заново
fun checkReceiptDate(date: LocalDate, today: LocalDate) {
    val (code, problem) = when {
        date.isAfter(today) -> "invalid_date" to "Дата получения не может быть позже сегодняшней."
        // дата может быть и верной: тогда с этим актом делать уже нечего — предлагаем загрузить другой
        // или убрать этот (бот добавляет кнопку по коду receipt_expired)
        date.isBefore(today.minusDays(RECEIPT_WINDOW_DAYS)) -> "receipt_expired" to
                "Срок по этому акту истёк: с даты получения прошло больше 30 дней, и по п. 5 приказа " +
                "Минстроя № 318/пр акт считается принятым. Если от УК пришёл другой акт — " +
                "загрузите его файлом в этот чат, если нет — нажмите «Убрать акт»."
        else -> return
    }
    throw ApiError(
        HttpStatusCode.BadRequest, code,
        "$problem Если дата введена с ошибкой — напишите правильную в формате ДД.ММ.ГГГГ",
    )
}

// receivedAt == null — оставить T0 моментом загрузки; вызывать только внутри tx { }
fun confirmReceiptTx(actId: Long, userId: Long, receivedAt: Instant?, zone: ZoneId): ResultRow {
    lockOpenReceiptTx(actId, zone)
    if (receivedAt != null) {
        Acts.update({ Acts.id eq actId }) {
            it[Acts.receivedAt] = receivedAt
            it[deadline10] = Deadlines.day10(receivedAt, zone)
            it[deadline30] = Deadlines.day30(receivedAt, zone)
        }
        logEvent(actId, "RECEIPT_DATE_SET", userId, "date=${receivedAt.atZone(zone).toLocalDate()}")
    }
    logEvent(actId, "RECEIPT_CONFIRMED", userId)
    return Acts.selectAll().where { Acts.id eq actId }.single()
}

val ACTIVE_STATUSES = listOf(ActStatus.RECEIVED, ActStatus.COLLECTING, ActStatus.REVIEW)

// последний акт дома, который ещё в работе: активный статус и не заархивирован; вызывать только внутри tx { }
fun activeActTx(houseId: Long): ResultRow? =
    Acts.selectAll()
        .where { (Acts.houseId eq houseId) and (Acts.status inList ACTIVE_STATUSES) and Acts.archivedAt.isNull() }
        .orderBy(Acts.createdAt to SortOrder.DESC)
        .limit(1)
        .singleOrNull()

// все акты дома, которые показываем в боте и в мини-аппе: любой статус, кроме архивных, свежие сверху.
// Один источник правды для меню бота и списка в мини-аппе; вызывать только внутри tx { }
fun houseActsTx(houseId: Long): List<ResultRow> =
    Acts.selectAll().where { (Acts.houseId eq houseId) and Acts.archivedAt.isNull() }
        .orderBy(Acts.createdAt to SortOrder.DESC)
        .toList()

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
