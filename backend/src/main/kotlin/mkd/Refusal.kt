package mkd

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

@Serializable data class Objection(
    val itemId: Long, val lineNo: Int, val itemName: String,
    val fact: String, val groundRef: String, val groundText: String, val demand: String,
    val okCount: Int, val issueCount: Int, val photoCount: Int,
)
@Serializable data class RefusalDraft(val objections: List<Objection>, val noObjectionLineNos: List<Int>)
@Serializable data class RefusalDto(
    val id: Long, val revision: Int, val place: String, val objections: List<Objection>,
    val noObjectionLineNos: List<Int>, val confirmedAt: String?, val sentAt: String?,
)

data class DraftRemark(val verdict: Verdict, val text: String, val photoCount: Int)
data class DraftItem(
    val itemId: Long, val lineNo: Int, val name: String, val workKind: String, val decision: Decision?,
    val remarks: List<DraftRemark>,
)
data class GroundRow(val legalRef: String, val wording: String, val demandTemplate: String)

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
                demand = ground.demandTemplate.replace("{item}", item.name),
                okCount = item.remarks.count { it.verdict == Verdict.OK },
                issueCount = item.remarks.count { it.verdict == Verdict.ISSUE },
                photoCount = item.remarks.filter { it.verdict == Verdict.ISSUE }.sumOf { it.photoCount },
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
            throw ApiError(HttpStatusCode.Conflict, "wrong_status", "Отказ можно формировать только во время сбора замечаний или на этапе решения")
        }
        if (act[Acts.status] == ActStatus.COLLECTING) acts.closeCollection(actId, userId)

        val existing = tx { Refusals.selectAll().where { Refusals.actId eq actId }.singleOrNull() }
        if (existing != null && existing[Refusals.confirmedAt] != null) {
            throw ApiError(HttpStatusCode.Conflict, "refusal_confirmed", "Отказ уже подтверждён, редактирование недоступно")
        }
        if (existing != null && !rebuild) return refusalDtoOf(existing)

        remarks.ensureFormalized(actId)
        val items = draftItemsOf(actId)
        val grounds = groundsMap()
        val draft = buildDraft(items, grounds)
        if (draft.objections.isEmpty()) {
            throw ApiError(HttpStatusCode.Conflict, "no_objections", "Нет оспариваемых позиций с фото — подпишите акт или отметьте позиции для возражения")
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

    private suspend fun draftItemsOf(actId: Long): List<DraftItem> = tx {
        ActItems.selectAll().where { ActItems.actId eq actId }.orderBy(ActItems.lineNo to SortOrder.ASC).map { row ->
            val itemId = row[ActItems.id].value
            val draftRemarks = Remarks.selectAll().where { Remarks.itemId eq itemId }.map {
                val text = it[Remarks.formalizedText] ?: it[Remarks.originalText] ?: ""
                DraftRemark(it[Remarks.verdict], text, photosOfTx(it[Remarks.id].value).size)
            }
            DraftItem(itemId, row[ActItems.lineNo], row[ActItems.name], row[ActItems.workKind], row[ActItems.decision], draftRemarks)
        }
    }

    private suspend fun groundsMap(): Map<String, GroundRow> = tx {
        Grounds.selectAll().associate { it[Grounds.workKind] to GroundRow(it[Grounds.legalRef], it[Grounds.wording], it[Grounds.demandTemplate]) }
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
