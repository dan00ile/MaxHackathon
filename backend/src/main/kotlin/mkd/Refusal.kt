package mkd

import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// Блок спорной позиции по шаблону мотивированного отказа: «Указано в акте» (actWording),
// «По данным опроса жильцов» (fact), «Возражение» (demand). fact и demand председатель правит в мини-аппе.
@Serializable
data class Objection(
    val itemId: Long, val lineNo: Int, val itemName: String,
    val fact: String, val groundRef: String, val groundText: String, val demand: String,
    val okCount: Int, val issueCount: Int, val photoCount: Int,
    val actWording: String = "", // пусто в черновиках, собранных до шаблона, — PDF подставит название
)

@Serializable
data class RefusalDraft(val objections: List<Objection>, val noObjectionLineNos: List<Int>)
@Serializable
data class RefusalDto(
    val id: Long, val revision: Int, val place: String, val objections: List<Objection>,
    val noObjectionLineNos: List<Int>, val confirmedAt: String?, val sentAt: String?,
)

@Serializable
data class ObjectionEdit(val itemId: Long, val fact: String, val demand: String)
@Serializable
data class RefusalEdit(val place: String, val objections: List<ObjectionEdit>)

data class DraftRemark(val verdict: Verdict, val text: String, val photoCount: Int)
data class DraftItem(
    val itemId: Long, val lineNo: Int, val name: String, val workKind: String, val decision: Decision?,
    val remarks: List<DraftRemark>,
    val periodicity: String = "", val volume: String = "", val cost: String = "",
)

data class GroundRow(val legalRef: String, val wording: String)

// «Указано в акте»: как позиция записана у УК — название и заявленные периодичность, объём, стоимость
fun actWording(item: DraftItem): String = listOfNotNull(
    "«${item.name}»",
    item.periodicity.ifBlank { null }?.let { "периодичность — $it" },
    item.volume.ifBlank { null }?.let { "объём — $it" },
    item.cost.ifBlank { null }?.let { "стоимость — $it руб." },
).joinToString(", ")

// «Возражение»: чем именно факт расходится с требованием — формулировка и ссылка из справочника оснований
fun objectionText(ground: GroundRow): String =
    "Работа не выполнена или выполнена с недостатками, тогда как " +
        "${ground.wording.replaceFirstChar { it.lowercase() }} (${ground.legalRef})."

// Возражение формируется, только если позиция оспорена и есть ISSUE-замечания с фото (FR-E5);
// иначе позиция считается принятой без возражений (FR-G1 — частичный отказ).
fun buildDraft(items: List<DraftItem>, grounds: Map<String, GroundRow>): RefusalDraft {
    val objections = mutableListOf<Objection>()
    val noObjectionLineNos = mutableListOf<Int>()
    items.forEach { item ->
        val issueWithPhoto = item.remarks.filter { it.verdict == Verdict.ISSUE && it.photoCount > 0 }
        if (item.decision != Decision.DISPUTE || issueWithPhoto.isEmpty()) {
            noObjectionLineNos.add(item.lineNo)
            return@forEach
        }
        val texts = issueWithPhoto.map { it.text }.distinct()
        val fact = if (texts.size == 1) texts[0] else texts.mapIndexed { i, t -> "${i + 1}) $t" }.joinToString("\n")
        val ground = grounds[item.workKind] ?: grounds.getValue("OTHER")
        objections.add(
            Objection(
                itemId = item.itemId, lineNo = item.lineNo, itemName = item.name,
                fact = fact, groundRef = ground.legalRef, groundText = ground.wording,
                demand = objectionText(ground),
                okCount = item.remarks.count { it.verdict == Verdict.OK },
                issueCount = item.remarks.count { it.verdict == Verdict.ISSUE },
                photoCount = item.remarks.filter { it.verdict == Verdict.ISSUE }.sumOf { it.photoCount },
                actWording = actWording(item),
            ),
        )
    }
    return RefusalDraft(objections.sortedBy { it.lineNo }, noObjectionLineNos.sorted())
}

class RefusalService(
    private val cfg: Config,
    private val max: MaxBotClient,
    private val remarks: RemarkService,
    private val acts: ActService,
) {
    suspend fun draft(actId: Long, userId: Long, rebuild: Boolean): RefusalDto {
        val act = acts.requireChairmanOf(actId, userId)
        if (act[Acts.status] != ActStatus.COLLECTING && act[Acts.status] != ActStatus.REVIEW) {
            throw ApiError(
                HttpStatusCode.Conflict,
                "wrong_status",
                "Отказ можно формировать только во время сбора замечаний или на этапе решения"
            )
        }
        if (act[Acts.status] == ActStatus.COLLECTING) acts.closeCollection(actId, userId)

        val existing = tx { Refusals.selectAll().where { Refusals.actId eq actId }.singleOrNull() }
        if (existing != null && existing[Refusals.confirmedAt] != null) {
            throw ApiError(
                HttpStatusCode.Conflict,
                "refusal_confirmed",
                "Отказ уже подтверждён, редактирование недоступно"
            )
        }
        if (existing != null && !rebuild) return refusalDtoOf(existing)

        remarks.ensureFormalized(actId)
        val items = draftItemsOf(actId)
        val grounds = groundsMap()
        val draft = buildDraft(items, grounds)
        if (draft.objections.isEmpty()) {
            throw ApiError(
                HttpStatusCode.Conflict,
                "no_objections",
                "Нет оспариваемых позиций с фото — подпишите акт или отметьте позиции для возражения"
            )
        }

        val houseId = act[Acts.houseId].value
        val houseAddress = tx { Houses.select(Houses.address).where { Houses.id eq houseId }.single()[Houses.address] }
        val draftJson = AppJson.encodeToString(draft)

        val row = tx {
            if (existing != null) {
                val revision = existing[Refusals.revision] + 1
                Refusals.update({ Refusals.actId eq actId }) {
                    it[Refusals.draftJson] = draftJson
                    it[Refusals.revision] = revision
                }
            } else {
                Refusals.insert {
                    it[Refusals.actId] = EntityID(actId, Acts)
                    it[Refusals.draftJson] = draftJson
                    it[Refusals.place] = houseAddress
                    it[Refusals.revision] = 1
                    it[Refusals.createdAt] = Instant.now()
                }
            }
            logEvent(actId, "REFUSAL_DRAFTED", userId, "revision=${(existing?.get(Refusals.revision) ?: 0) + 1}")
            Refusals.selectAll().where { Refusals.actId eq actId }.single()
        }
        return refusalDtoOf(row)
    }

    suspend fun get(actId: Long, userId: Long): RefusalDto {
        acts.requireChairmanOf(actId, userId)
        val row = tx { Refusals.selectAll().where { Refusals.actId eq actId }.singleOrNull() }
            ?: throw ApiError(HttpStatusCode.NotFound, "no_refusal", "Черновик отказа не найден")
        return refusalDtoOf(row)
    }

    suspend fun edit(actId: Long, userId: Long, editInput: RefusalEdit): RefusalDto {
        acts.requireChairmanOf(actId, userId)
        val existing = tx { Refusals.selectAll().where { Refusals.actId eq actId }.singleOrNull() }
            ?: throw ApiError(HttpStatusCode.NotFound, "no_refusal", "Черновик отказа не найден")
        if (existing[Refusals.confirmedAt] != null) {
            throw ApiError(
                HttpStatusCode.Conflict,
                "refusal_confirmed",
                "Отказ уже подтверждён, редактирование недоступно"
            )
        }
        if (editInput.objections.any { it.fact.isBlank() || it.demand.isBlank() }) {
            throw ApiError(
                HttpStatusCode.BadRequest,
                "invalid_input",
                "Поля «По данным опроса жильцов» и «Возражение» не могут быть пустыми"
            )
        }
        val current = AppJson.decodeFromString<RefusalDraft>(existing[Refusals.draftJson])
        val knownItemIds = current.objections.map { it.itemId }.toSet()
        if (editInput.objections.any { it.itemId !in knownItemIds }) {
            throw ApiError(HttpStatusCode.BadRequest, "unknown_item", "Позиция не найдена в черновике")
        }
        val updatedObjections = current.objections.map { o ->
            val patch = editInput.objections.firstOrNull { it.itemId == o.itemId }
            if (patch != null) o.copy(fact = patch.fact.trim(), demand = patch.demand.trim()) else o
        }
        val newRevision = existing[Refusals.revision] + 1
        val row = tx {
            Refusals.update({ Refusals.actId eq actId }) {
                it[draftJson] = AppJson.encodeToString(current.copy(objections = updatedObjections))
                it[place] = editInput.place.trim().ifBlank { existing[Refusals.place] }
                it[revision] = newRevision
            }
            logEvent(actId, "REFUSAL_EDITED", userId, "revision=$newRevision")
            Refusals.selectAll().where { Refusals.actId eq actId }.single()
        }
        return refusalDtoOf(row)
    }

    suspend fun confirm(actId: Long, userId: Long): RefusalDto {
        val act = acts.requireChairmanOf(actId, userId)
        val existing = tx { Refusals.selectAll().where { Refusals.actId eq actId }.singleOrNull() }
            ?: throw ApiError(HttpStatusCode.NotFound, "no_refusal", "Черновик отказа не найден")
        if (existing[Refusals.confirmedAt] != null) {
            throw ApiError(HttpStatusCode.Conflict, "refusal_confirmed", "Отказ уже подтверждён")
        }
        val draftData = AppJson.decodeFromString<RefusalDraft>(existing[Refusals.draftJson])
        val objectionByItemId = draftData.objections.associateBy { it.itemId }

        data class PhotoRow(
            val attId: Long, val lineNo: Int, val itemName: String, val filePath: String,
            val authorName: String, val uploadedAt: Instant,
        )

        val photoRows = tx {
            objectionByItemId.keys.flatMap { itemId ->
                val objection = objectionByItemId.getValue(itemId)
                Remarks.selectAll().where { (Remarks.itemId eq itemId) and (Remarks.verdict eq Verdict.ISSUE) }
                    .flatMap { remark ->
                        Attachments.selectAll().where { Attachments.remarkId eq remark[Remarks.id].value }.map { att ->
                            val authorName = Users.select(Users.name).where { Users.id eq att[Attachments.authorId] }
                                .singleOrNull()?.get(Users.name) ?: "Житель"
                            PhotoRow(
                                att[Attachments.id].value,
                                objection.lineNo,
                                objection.itemName,
                                att[Attachments.filePath],
                                authorName,
                                att[Attachments.uploadedAt]
                            )
                        }
                    }
            }.sortedWith(compareBy({ it.lineNo }, { it.uploadedAt }))
        }
        val numbered = photoRows.mapIndexed { idx, row -> (idx + 1) to row }
        tx {
            numbered.forEach { (no, row) ->
                Attachments.update({ Attachments.id eq row.attId }) {
                    it[registryNo] = no
                }
            }
        }

        val houseId = act[Acts.houseId].value
        val house = tx { Houses.selectAll().where { Houses.id eq houseId }.single() }
        val uk =
            tx { ManagementCompanies.selectAll().where { ManagementCompanies.id eq house[Houses.ukId].value }.single() }
        val chairman =
            tx { Chairmen.selectAll().where { (Chairmen.userId eq userId) and (Chairmen.houseId eq houseId) }.single() }
        val composedAt = ZonedDateTime.now(cfg.zone)
        val photos = numbered.map { (no, row) ->
            PhotoPage(
                no,
                row.lineNo,
                row.itemName,
                row.authorName,
                row.uploadedAt.atZone(cfg.zone),
                File(row.filePath)
            )
        }

        val bytes = Pdf.refusal(
            RefusalPdfData(
                houseAddress = house[Houses.address],
                ukName = uk[ManagementCompanies.name],
                ukAddress = uk[ManagementCompanies.address],
                ukRepresentative = uk[ManagementCompanies.representative],
                exchangeMethod = uk[ManagementCompanies.exchangeMethod],
                actNumber = act[Acts.number],
                formedDate = act[Acts.formedDate],
                period = act[Acts.period],
                objections = draftData.objections,
                noObjectionLineNos = draftData.noObjectionLineNos,
                photos = photos,
                chairmanFio = chairman[Chairmen.fullName],
                place = existing[Refusals.place],
                composedAt = composedAt,
                demo = cfg.demoMode,
            ),
        )
        val dir = Path.of(cfg.filesDir, "pdf")
        Files.createDirectories(dir)
        val pdfPath = dir.resolve("act-$actId-refusal-r${existing[Refusals.revision]}.pdf")
        Files.write(pdfPath, bytes)

        tx {
            Refusals.update({ Refusals.actId eq actId }) {
                it[confirmedAt] = Instant.now()
                it[confirmedBy] = userId
                it[Refusals.pdfPath] = pdfPath.toString()
            }
            logEvent(actId, "REFUSAL_CONFIRMED", userId)
        }

        val number = act[Acts.number] ?: "без номера"
        max.sendFile(
            userId, bytes, "otkaz-akt-$number.pdf",
            "Мотивированный отказ готов. Перешлите файл в УК (${uk[ManagementCompanies.exchangeMethod]}) и нажмите кнопку ниже — мы зафиксируем время отправки.",
            listOf(listOf(cb("Отправил исполнителю", "sent:$actId"))),
        )

        return tx { refusalDtoOf(Refusals.selectAll().where { Refusals.actId eq actId }.single()) }
    }

    suspend fun markSent(actId: Long, userId: Long) {
        val act = acts.requireChairmanOf(actId, userId)
        val row = tx { Refusals.selectAll().where { Refusals.actId eq actId }.singleOrNull() }
            ?: throw ApiError(HttpStatusCode.NotFound, "no_refusal", "Черновик отказа не найден")
        if (row[Refusals.confirmedAt] == null) {
            throw ApiError(HttpStatusCode.Conflict, "not_confirmed", "Сначала подтвердите отказ")
        }
        val dateTimeFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(cfg.zone)
        val alreadySentAt = row[Refusals.sentAt]
        if (alreadySentAt != null) {
            throw ApiError(HttpStatusCode.Conflict, "already_sent", "Уже отмечено ${dateTimeFmt.format(alreadySentAt)}")
        }

        val houseId = act[Acts.houseId].value
        val uk = tx {
            val house = Houses.selectAll().where { Houses.id eq houseId }.single()
            ManagementCompanies.selectAll().where { ManagementCompanies.id eq house[Houses.ukId].value }.single()
        }
        val sentAt = Instant.now()
        tx {
            Refusals.update({ Refusals.actId eq actId }) { it[Refusals.sentAt] = sentAt }
            Acts.update({ Acts.id eq actId }) { it[status] = ActStatus.REJECTED }
            logEvent(actId, "REFUSAL_SENT", userId, "method=${uk[ManagementCompanies.exchangeMethod]}")
        }

        max.sendText(
            userId,
            "Время отправки зафиксировано: ${dateTimeFmt.format(sentAt)}. Акт в статусе «Отказ направлен, ожидается новый акт». " +
                    "Когда УК пришлёт новый акт — просто загрузите его сюда.",
        )

        val objectionsCount = AppJson.decodeFromString<RefusalDraft>(row[Refusals.draftJson]).objections.size
        val number = act[Acts.number] ?: "без номера"
        val residents =
            tx { Users.selectAll().where { (Users.houseId eq houseId) and (Users.id neq userId) }.map { it[Users.id] } }
        residents.forEach { uid ->
            runCatching {
                max.sendText(
                    uid,
                    "Председатель направил в УК мотивированный отказ по акту № $number (оспорено позиций: $objectionsCount). Спасибо за ваши отметки!"
                )
            }
        }
    }

    private suspend fun draftItemsOf(actId: Long): List<DraftItem> = tx {
        ActItems.selectAll().where { ActItems.actId eq actId }.orderBy(ActItems.lineNo to SortOrder.ASC).map { row ->
            val itemId = row[ActItems.id].value
            val draftRemarks = Remarks.selectAll().where { Remarks.itemId eq itemId }.map {
                val text = it[Remarks.formalizedText] ?: it[Remarks.originalText] ?: ""
                DraftRemark(it[Remarks.verdict], text, photosOfTx(it[Remarks.id].value).size)
            }
            DraftItem(
                itemId,
                row[ActItems.lineNo],
                row[ActItems.name],
                row[ActItems.workKind],
                row[ActItems.decision],
                draftRemarks,
                row[ActItems.periodicity],
                row[ActItems.volume],
                row[ActItems.cost],
            )
        }
    }

    private suspend fun groundsMap(): Map<String, GroundRow> = tx {
        Grounds.selectAll().associate {
            it[Grounds.workKind] to GroundRow(it[Grounds.legalRef], it[Grounds.wording])
        }
    }

    private fun refusalDtoOf(row: ResultRow): RefusalDto {
        val draft = AppJson.decodeFromString<RefusalDraft>(row[Refusals.draftJson])
        return RefusalDto(
            id = row[Refusals.id].value, revision = row[Refusals.revision], place = row[Refusals.place],
            objections = draft.objections, noObjectionLineNos = draft.noObjectionLineNos,
            confirmedAt = row[Refusals.confirmedAt]?.toString(), sentAt = row[Refusals.sentAt]?.toString(),
        )
    }
}
