package mkd

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ApiError(val status: HttpStatusCode, val code: String, override val message: String) : Exception(message)

@Serializable data class ErrorDto(val error: String, val message: String)

fun ApplicationCall.authUser(cfg: Config): InitData.Result {
    val initDataHeader = request.header("X-Max-Init-Data")
    if (initDataHeader != null) {
        return InitData.validate(initDataHeader, cfg.maxToken)
            ?: throw ApiError(HttpStatusCode.Unauthorized, "unauthorized", "Не удалось проверить initData")
    }
    if (cfg.devAuth) {
        val devUserId = request.header("X-Dev-User-Id")?.toLongOrNull()
        if (devUserId != null) return InitData.Result(devUserId, "Dev", null)
    }
    throw ApiError(HttpStatusCode.Unauthorized, "unauthorized", "Нет данных авторизации")
}

data class Roles(val houseId: Long?, val resident: Boolean, val chairman: Boolean, val chairmanPending: Boolean)

// вызывать только внутри tx { }
fun rolesOfTx(userId: Long): Roles {
    val user = Users.select(Users.houseId).where { Users.id eq userId }.singleOrNull()
    val houseId = user?.get(Users.houseId)?.value
    val chairmanRow = houseId?.let {
        Chairmen.select(Chairmen.confirmedAt)
            .where { (Chairmen.userId eq userId) and (Chairmen.houseId eq it) }
            .singleOrNull()
    }
    return Roles(
        houseId = houseId,
        resident = houseId != null,
        chairman = chairmanRow != null && chairmanRow[Chairmen.confirmedAt] != null,
        chairmanPending = chairmanRow != null && chairmanRow[Chairmen.confirmedAt] == null,
    )
}

suspend fun rolesOf(userId: Long): Roles = tx { rolesOfTx(userId) }

// последний акт дома в статусе RECEIVED/COLLECTING/REVIEW
suspend fun activeActIdOf(houseId: Long): Long? = tx {
    Acts.select(Acts.id)
        .where { (Acts.houseId eq houseId) and (Acts.status inList listOf(ActStatus.RECEIVED, ActStatus.COLLECTING, ActStatus.REVIEW)) }
        .orderBy(Acts.createdAt to SortOrder.DESC)
        .limit(1)
        .singleOrNull()?.get(Acts.id)?.value
}

@Serializable data class MeDto(
    val userId: Long, val name: String,
    val registered: Boolean,
    val houseId: Long?, val houseAddress: String?,
    val roles: List<String>,
    val chairmanPending: Boolean,
    val activeActId: Long?,
    val startParam: String?,
)

@Serializable data class WorkKindDto(val code: String, val title: String)
@Serializable data class PhotoDto(val id: Long, val url: String)
@Serializable data class StatsDto(val ok: Int, val issue: Int, val issueWithPhoto: Int)
@Serializable data class MyRemarkDto(val verdict: String, val text: String?, val photos: List<PhotoDto>)
@Serializable data class RemarkDto(
    val id: Long, val verdict: String, val text: String?, val formalized: String?,
    val llmStatus: String, val photos: List<PhotoDto>,
)
@Serializable data class ItemDto(
    val id: Long, val lineNo: Int, val name: String, val periodicity: String, val volume: String, val cost: String,
    val workKind: String, val decision: String?,
    val stats: StatsDto,
    val my: MyRemarkDto?,
    val remarks: List<RemarkDto>?,
)
@Serializable data class ActDto(
    val id: Long, val houseAddress: String, val number: String?, val formedDate: String?, val period: String?,
    val actType: String, val status: String, val recognition: String,
    val receivedAt: String, val deadline10: String, val deadline30: String,
    val daysLeft10: Long, val daysLeft30: Long,
    val isChairman: Boolean, val isResident: Boolean,
    val items: List<ItemDto>, val workKinds: List<WorkKindDto>,
    val hasRefusal: Boolean,
)
@Serializable data class ItemInput(
    val id: Long? = null, val lineNo: Int, val name: String, val periodicity: String = "",
    val volume: String = "", val cost: String = "", val workKind: String = "OTHER",
)
@Serializable data class CardInput(val number: String?, val formedDate: String?, val period: String?, val items: List<ItemInput>)

private suspend fun actDto(cfg: Config, actId: Long, userId: Long): ActDto = tx {
    val act = Acts.selectAll().where { Acts.id eq actId }.single()
    val houseId = act[Acts.houseId].value
    val houseAddress = Houses.select(Houses.address).where { Houses.id eq houseId }.single()[Houses.address]
    val roles = rolesOfTx(userId)
    val now = Instant.now()

    val itemRows = ActItems.selectAll().where { ActItems.actId eq actId }.orderBy(ActItems.lineNo to SortOrder.ASC).toList()
    val items = itemRows.map { row ->
        val itemId = row[ActItems.id].value
        val itemRemarks = Remarks.selectAll().where { Remarks.itemId eq itemId }.toList()
        fun photosOf(remarkId: Long) = Attachments.selectAll().where { Attachments.remarkId eq remarkId }
            .map { PhotoDto(it[Attachments.id].value, "/api/photos/${it[Attachments.id].value}") }
        val ok = itemRemarks.count { it[Remarks.verdict] == Verdict.OK }
        val issue = itemRemarks.count { it[Remarks.verdict] == Verdict.ISSUE }
        val issueWithPhoto = itemRemarks.count { it[Remarks.verdict] == Verdict.ISSUE && photosOf(it[Remarks.id].value).isNotEmpty() }
        val my = if (roles.resident) {
            itemRemarks.firstOrNull { it[Remarks.authorId] == userId }
                ?.let { MyRemarkDto(it[Remarks.verdict].name, it[Remarks.originalText], photosOf(it[Remarks.id].value)) }
        } else null
        val remarksDto = if (roles.chairman) {
            itemRemarks.map {
                RemarkDto(
                    it[Remarks.id].value, it[Remarks.verdict].name, it[Remarks.originalText], it[Remarks.formalizedText],
                    it[Remarks.llmStatus].name, photosOf(it[Remarks.id].value),
                )
            }
        } else null
        ItemDto(
            itemId, row[ActItems.lineNo], row[ActItems.name], row[ActItems.periodicity], row[ActItems.volume],
            row[ActItems.cost], row[ActItems.workKind], row[ActItems.decision]?.name,
            StatsDto(ok, issue, issueWithPhoto), my, remarksDto,
        )
    }
    val workKinds = Grounds.selectAll().map { WorkKindDto(it[Grounds.workKind], it[Grounds.workKindTitle]) }
    val hasRefusal = Refusals.selectAll().where { Refusals.actId eq actId }.count() > 0

    ActDto(
        id = actId, houseAddress = houseAddress, number = act[Acts.number], formedDate = act[Acts.formedDate]?.toString(),
        period = act[Acts.period], actType = act[Acts.actType], status = act[Acts.status].name, recognition = act[Acts.recognition].name,
        receivedAt = act[Acts.receivedAt].toString(), deadline10 = act[Acts.deadline10].toString(), deadline30 = act[Acts.deadline30].toString(),
        daysLeft10 = Deadlines.daysLeft(act[Acts.deadline10], now, cfg.zone), daysLeft30 = Deadlines.daysLeft(act[Acts.deadline30], now, cfg.zone),
        isChairman = roles.chairman, isResident = roles.resident,
        items = items, workKinds = workKinds, hasRefusal = hasRefusal,
    )
}

fun Route.api(cfg: Config, max: MaxBotClient, acts: ActService) {
    get("/api/acts/{id}") {
        val auth = call.authUser(cfg)
        val actId = call.parameters["id"]!!.toLong()
        acts.requireMemberOf(actId, auth.userId)
        call.respond(actDto(cfg, actId, auth.userId))
    }

    put("/api/acts/{id}/card") {
        val auth = call.authUser(cfg)
        val actId = call.parameters["id"]!!.toLong()
        val act = acts.requireChairmanOf(actId, auth.userId)
        if (act[Acts.status] != ActStatus.RECEIVED) {
            throw ApiError(HttpStatusCode.Conflict, "card_locked", "Карточку акта уже нельзя менять")
        }
        val input = call.receive<CardInput>()
        if (input.items.any { it.name.isBlank() }) {
            throw ApiError(HttpStatusCode.BadRequest, "invalid_item", "Название позиции не может быть пустым")
        }
        if (input.items.map { it.lineNo }.distinct().size != input.items.size) {
            throw ApiError(HttpStatusCode.BadRequest, "duplicate_line_no", "Номера строк должны быть уникальны")
        }
        tx {
            Acts.update({ Acts.id eq actId }) {
                it[number] = input.number?.trim()?.ifBlank { null }
                it[period] = input.period?.trim()?.ifBlank { null }
                it[formedDate] = input.formedDate?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() }
            }
            val existingIds = ActItems.selectAll().where { ActItems.actId eq actId }.map { it[ActItems.id].value }.toSet()
            val keepIds = input.items.mapNotNull { it.id }.toSet()
            (existingIds - keepIds).forEach { id -> ActItems.deleteWhere { Op.build { ActItems.id eq id } } }
            input.items.forEach { item ->
                if (item.id != null && item.id in existingIds) {
                    ActItems.update({ ActItems.id eq item.id }) {
                        it[lineNo] = item.lineNo
                        it[name] = item.name.trim()
                        it[periodicity] = item.periodicity
                        it[volume] = item.volume
                        it[cost] = item.cost
                        it[workKind] = item.workKind
                    }
                } else {
                    ActItems.insert {
                        it[ActItems.actId] = EntityID(actId, Acts)
                        it[lineNo] = item.lineNo
                        it[name] = item.name.trim()
                        it[periodicity] = item.periodicity
                        it[volume] = item.volume
                        it[cost] = item.cost
                        it[workKind] = item.workKind
                    }
                }
            }
            logEvent(actId, "CARD_EDITED", auth.userId)
        }
        call.respond(actDto(cfg, actId, auth.userId))
    }

    post("/api/acts/{id}/open-collection") {
        val auth = call.authUser(cfg)
        val actId = call.parameters["id"]!!.toLong()
        val act = acts.requireChairmanOf(actId, auth.userId)
        if (act[Acts.status] != ActStatus.RECEIVED) {
            throw ApiError(HttpStatusCode.Conflict, "card_locked", "Карточку акта уже нельзя менять")
        }
        val itemCount = tx { ActItems.selectAll().where { ActItems.actId eq actId }.count() }
        if (itemCount == 0L) throw ApiError(HttpStatusCode.Conflict, "no_items", "Добавьте хотя бы одну позицию перед началом сбора")

        val houseId = act[Acts.houseId].value
        tx {
            Acts.update({ Acts.id eq actId }) { it[status] = ActStatus.COLLECTING }
            logEvent(actId, "COLLECTION_OPENED", auth.userId)
        }
        val period = act[Acts.period]
        val deadline30 = act[Acts.deadline30]
        val residents = tx { Users.selectAll().where { (Users.houseId eq houseId) and (Users.id neq auth.userId) }.map { it[Users.id] } }
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val text = "Председатель открыл проверку акта работ УК за ${period ?: "—"}. Отметьте, что сделано, а что нет — " +
            "это займёт 2 минуты. Последний день приёма замечаний — ${fmt.format(deadline30)}."
        residents.forEach { uid ->
            runCatching { max.sendText(uid, text, listOf(listOf(link("Отметить работы", appLink("act_$actId"))))) }
            delay(50)
        }
        call.respond(actDto(cfg, actId, auth.userId))
    }

    get("/api/me") {
        val auth = call.authUser(cfg)
        val dto = tx {
            val user = Users.selectAll().where { Users.id eq auth.userId }.singleOrNull()
            val roles = rolesOfTx(auth.userId)
            val houseAddress = roles.houseId?.let {
                Houses.select(Houses.address).where { Houses.id eq it }.singleOrNull()?.get(Houses.address)
            }
            val activeActId = roles.houseId?.let { houseId ->
                Acts.select(Acts.id)
                    .where { (Acts.houseId eq houseId) and (Acts.status inList listOf(ActStatus.RECEIVED, ActStatus.COLLECTING, ActStatus.REVIEW)) }
                    .orderBy(Acts.createdAt to SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()?.get(Acts.id)?.value
            }
            val roleNames = buildList {
                if (roles.resident) add("RESIDENT")
                if (roles.chairman) add("CHAIRMAN")
            }
            MeDto(
                userId = auth.userId,
                name = user?.get(Users.name) ?: auth.name,
                registered = user != null,
                houseId = roles.houseId,
                houseAddress = houseAddress,
                roles = roleNames,
                chairmanPending = roles.chairmanPending,
                activeActId = activeActId,
                startParam = auth.startParam,
            )
        }
        call.respond(dto)
    }
}
