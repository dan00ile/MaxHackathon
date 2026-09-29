package mkd

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ApiError(val status: HttpStatusCode, val code: String, override val message: String) : Exception(message)

@Serializable
data class ErrorDto(val error: String, val message: String)

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

// вызывать только внутри tx { }
fun photosOfTx(remarkId: Long): List<PhotoDto> =
    Attachments.selectAll().where { Attachments.remarkId eq remarkId }
        .map { PhotoDto(it[Attachments.id].value, "/api/photos/${it[Attachments.id].value}") }

@Serializable
data class MeDto(
    val userId: Long, val name: String,
    val registered: Boolean,
    val houseId: Long?, val houseAddress: String?,
    val roles: List<String>,
    val chairmanPending: Boolean,
    val startParam: String?,
)

@Serializable
data class WorkKindDto(val code: String, val title: String)
@Serializable
data class PhotoDto(val id: Long, val url: String)
@Serializable
data class StatsDto(val ok: Int, val issue: Int, val issueWithPhoto: Int)
@Serializable
data class MyRemarkDto(val verdict: String, val text: String?, val photos: List<PhotoDto>)
@Serializable
data class RemarkDto(
    val id: Long, val verdict: String, val text: String?, val formalized: String?,
    val llmStatus: String, val photos: List<PhotoDto>,
)

@Serializable
data class ItemDto(
    val id: Long, val lineNo: Int, val name: String, val periodicity: String, val volume: String, val cost: String,
    val workKind: String, val decision: String?,
    val stats: StatsDto,
    val my: MyRemarkDto?,
    val remarks: List<RemarkDto>?,
)

@Serializable
data class ActDto(
    val id: Long, val houseAddress: String, val number: String?, val formedDate: String?, val period: String?,
    val actType: String, val status: String, val recognition: String,
    val receivedAt: String, val deadline10: String, val deadline30: String,
    val daysLeft10: Long, val daysLeft30: Long,
    val isChairman: Boolean, val isResident: Boolean,
    val items: List<ItemDto>, val workKinds: List<WorkKindDto>,
    val hasRefusal: Boolean,
)

// строка списка актов дома: без позиций, только то, что нужно карточке в списке
@Serializable
data class ActSummaryDto(
    val id: Long, val number: String?, val period: String?, val status: String, val recognition: String,
    val receivedAt: String, val deadline10: String, val deadline30: String, val daysLeft10: Long, val daysLeft30: Long,
    val itemCount: Int, val issueCount: Int,
    val myPending: Int?, // сколько позиций житель ещё не отметил — только пока идёт сбор
)

@Serializable
data class HouseActsDto(val houseAddress: String, val isChairman: Boolean, val acts: List<ActSummaryDto>)

@Serializable
data class ItemInput(
    val id: Long? = null, val lineNo: Int, val name: String, val periodicity: String = "",
    val volume: String = "", val cost: String = "", val workKind: String = "OTHER",
)

@Serializable
data class CardInput(val number: String?, val formedDate: String?, val period: String?, val items: List<ItemInput>)
@Serializable
data class MyRemarkInput(val verdict: String, val text: String? = null)
@Serializable
data class DecisionInput(val decision: String)

// вызывать только внутри tx { }
private fun itemDtoTx(row: ResultRow, roles: Roles, userId: Long): ItemDto {
    val itemId = row[ActItems.id].value
    val itemRemarks = Remarks.selectAll().where { Remarks.itemId eq itemId }.toList()
    val ok = itemRemarks.count { it[Remarks.verdict] == Verdict.OK }
    val issue = itemRemarks.count { it[Remarks.verdict] == Verdict.ISSUE }
    val issueWithPhoto =
        itemRemarks.count { it[Remarks.verdict] == Verdict.ISSUE && photosOfTx(it[Remarks.id].value).isNotEmpty() }
    val my = if (roles.resident) {
        itemRemarks.firstOrNull { it[Remarks.authorId] == userId }
            ?.let { MyRemarkDto(it[Remarks.verdict].name, it[Remarks.originalText], photosOfTx(it[Remarks.id].value)) }
    } else null
    val remarksDto = if (roles.chairman) {
        itemRemarks.map {
            RemarkDto(
                it[Remarks.id].value, it[Remarks.verdict].name, it[Remarks.originalText], it[Remarks.formalizedText],
                it[Remarks.llmStatus].name, photosOfTx(it[Remarks.id].value),
            )
        }
    } else null
    return ItemDto(
        itemId, row[ActItems.lineNo], row[ActItems.name], row[ActItems.periodicity], row[ActItems.volume],
        row[ActItems.cost], row[ActItems.workKind], row[ActItems.decision]?.name,
        StatsDto(ok, issue, issueWithPhoto), my, remarksDto,
    )
}

private suspend fun itemDto(itemId: Long, userId: Long): ItemDto = tx {
    val row = ActItems.selectAll().where { ActItems.id eq itemId }.single()
    itemDtoTx(row, rolesOfTx(userId), userId)
}

private suspend fun actDto(cfg: Config, actId: Long, userId: Long): ActDto = tx {
    val act = Acts.selectAll().where { Acts.id eq actId }.single()
    val houseId = act[Acts.houseId].value
    val houseAddress = Houses.select(Houses.address).where { Houses.id eq houseId }.single()[Houses.address]
    val roles = rolesOfTx(userId)
    val now = Instant.now()

    val itemRows =
        ActItems.selectAll().where { ActItems.actId eq actId }.orderBy(ActItems.lineNo to SortOrder.ASC).toList()
    val items = itemRows.map { row -> itemDtoTx(row, roles, userId) }
    val workKinds = Grounds.selectAll().map { WorkKindDto(it[Grounds.workKind], it[Grounds.workKindTitle]) }
    val hasRefusal = Refusals.selectAll().where { Refusals.actId eq actId }.count() > 0

    ActDto(
        id = actId,
        houseAddress = houseAddress,
        number = act[Acts.number],
        formedDate = act[Acts.formedDate]?.toString(),
        period = act[Acts.period],
        actType = act[Acts.actType],
        status = act[Acts.status].name,
        recognition = act[Acts.recognition].name,
        receivedAt = act[Acts.receivedAt].toString(),
        deadline10 = act[Acts.deadline10].toString(),
        deadline30 = act[Acts.deadline30].toString(),
        daysLeft10 = Deadlines.daysLeft(act[Acts.deadline10], now, cfg.zone),
        daysLeft30 = Deadlines.daysLeft(act[Acts.deadline30], now, cfg.zone),
        isChairman = roles.chairman,
        isResident = roles.resident,
        items = items,
        workKinds = workKinds,
        hasRefusal = hasRefusal,
    )
}

private suspend fun houseActsDto(cfg: Config, userId: Long): HouseActsDto = tx {
    val roles = rolesOfTx(userId)
    val houseId = roles.houseId
        ?: throw ApiError(HttpStatusCode.Forbidden, "no_house", "Сначала выберите дом в боте")
    val address = Houses.select(Houses.address).where { Houses.id eq houseId }.single()[Houses.address]
    val now = Instant.now()
    val summaries = houseActsTx(houseId).map { act ->
        val actId = act[Acts.id].value
        val itemIds = ActItems.select(ActItems.id).where { ActItems.actId eq actId }.map { it[ActItems.id] }
        val remarks = if (itemIds.isEmpty()) emptyList()
        else Remarks.select(Remarks.verdict, Remarks.authorId).where { Remarks.itemId inList itemIds }.toList()
        ActSummaryDto(
            id = actId, number = act[Acts.number], period = act[Acts.period],
            status = act[Acts.status].name, recognition = act[Acts.recognition].name,
            receivedAt = act[Acts.receivedAt].toString(),
            deadline10 = act[Acts.deadline10].toString(), deadline30 = act[Acts.deadline30].toString(),
            daysLeft10 = Deadlines.daysLeft(act[Acts.deadline10], now, cfg.zone),
            daysLeft30 = Deadlines.daysLeft(act[Acts.deadline30], now, cfg.zone),
            itemCount = itemIds.size,
            issueCount = remarks.count { it[Remarks.verdict] == Verdict.ISSUE },
            myPending = if (act[Acts.status] == ActStatus.COLLECTING) {
                itemIds.size - remarks.count { it[Remarks.authorId] == userId }
            } else null,
        )
    }
    HouseActsDto(address, roles.chairman, summaries)
}

fun Route.api(cfg: Config, max: MaxBotClient, acts: ActService, remarks: RemarkService, refusal: RefusalService) {
    get("/api/acts") {
        val auth = call.authUser(cfg)
        call.respond(houseActsDto(cfg, auth.userId))
    }

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
            val existingIds =
                ActItems.selectAll().where { ActItems.actId eq actId }.map { it[ActItems.id].value }.toSet()
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
        if (itemCount == 0L) throw ApiError(
            HttpStatusCode.Conflict,
            "no_items",
            "Добавьте хотя бы одну позицию перед началом сбора"
        )

        val houseId = act[Acts.houseId].value
        tx {
            Acts.update({ Acts.id eq actId }) { it[status] = ActStatus.COLLECTING }
            logEvent(actId, "COLLECTION_OPENED", auth.userId)
        }
        val period = act[Acts.period]
        val deadline30 = act[Acts.deadline30]
        val residents = tx {
            Users.selectAll().where { (Users.houseId eq houseId) and (Users.id neq auth.userId) }.map { it[Users.id] }
        }
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val text =
            "Председатель открыл проверку акта работ УК за ${period ?: "—"}. Отметьте, что сделано, а что нет — " +
                    "это займёт 2 минуты. Последний день приёма замечаний — ${fmt.format(deadline30)}."
        residents.forEach { uid ->
            runCatching { max.sendText(uid, text, listOf(listOf(link("Отметить работы", appLink("act_$actId"))))) }
            delay(50)
        }
        call.respond(actDto(cfg, actId, auth.userId))
    }

    put("/api/items/{itemId}/my-remark") {
        val auth = call.authUser(cfg)
        val itemId = call.parameters["itemId"]!!.toLong()
        val input = call.receive<MyRemarkInput>()
        val verdict = runCatching { Verdict.valueOf(input.verdict) }.getOrElse {
            throw ApiError(HttpStatusCode.BadRequest, "invalid_verdict", "verdict должен быть OK или ISSUE")
        }
        remarks.saveMy(itemId, auth.userId, verdict, input.text)
        call.respond(itemDto(itemId, auth.userId)) // вместе со свежей статистикой — мини-апп сразу обновит шкалу
    }

    post("/api/items/{itemId}/my-remark/photos") {
        val auth = call.authUser(cfg)
        val itemId = call.parameters["itemId"]!!.toLong()
        var bytes: ByteArray? = null
        call.receiveMultipart(formFieldLimit = 10L * 1024 * 1024).forEachPart { part ->
            if (part is PartData.FileItem && part.name == "photo") {
                bytes = part.provider().readRemaining().readByteArray()
            }
            part.dispose()
        }
        val data = bytes ?: throw ApiError(HttpStatusCode.BadRequest, "no_photo", "Файл не передан")
        // тип берём из самого файла, а не из заголовка части: заголовок пишет клиент, и он может врать
        val mimeType = ActFile.sniffMime(data) ?: ""
        if (mimeType !in setOf("image/jpeg", "image/png")) {
            throw ApiError(HttpStatusCode.BadRequest, "invalid_mime", "Допустимы только JPEG и PNG")
        }
        if (data.size > 10 * 1024 * 1024) throw ApiError(HttpStatusCode.BadRequest, "too_large", "Файл больше 10 МБ")
        remarks.addPhoto(itemId, auth.userId, data, mimeType)
        call.respond(itemDto(itemId, auth.userId))
    }

    get("/api/photos/{id}") {
        val auth = call.authUser(cfg)
        val photoId = call.parameters["id"]!!.toLong()
        val (file, mime) = remarks.photoFile(photoId, auth.userId)
        call.respondBytes(file.readBytes(), ContentType.parse(mime))
    }

    post("/api/acts/{id}/close-collection") {
        val auth = call.authUser(cfg)
        val actId = call.parameters["id"]!!.toLong()
        acts.closeCollection(actId, auth.userId)
        call.respond(actDto(cfg, actId, auth.userId))
    }

    // та же подпись, что по кнопке в чате: PDF уходит председателю в чат, жители получают уведомление
    post("/api/acts/{id}/sign") {
        val auth = call.authUser(cfg)
        val actId = call.parameters["id"]!!.toLong()
        acts.sign(actId, auth.userId)
        call.respond(actDto(cfg, actId, auth.userId))
    }

    // POST, а не DELETE: CORS мини-аппа и так разрешает POST
    post("/api/acts/{id}/delete") {
        val auth = call.authUser(cfg)
        acts.delete(call.parameters["id"]!!.toLong(), auth.userId)
        call.respond(HttpStatusCode.NoContent)
    }

    put("/api/items/{itemId}/decision") {
        val auth = call.authUser(cfg)
        val itemId = call.parameters["itemId"]!!.toLong()
        val input = call.receive<DecisionInput>()
        val decision = runCatching { Decision.valueOf(input.decision) }.getOrElse {
            throw ApiError(HttpStatusCode.BadRequest, "invalid_decision", "decision должен быть ACCEPT или DISPUTE")
        }
        acts.setDecision(itemId, auth.userId, decision)
        call.respond(itemDto(itemId, auth.userId))
    }

    post("/api/acts/{id}/refusal/draft") {
        val auth = call.authUser(cfg)
        val actId = call.parameters["id"]!!.toLong()
        val rebuild = call.request.queryParameters["rebuild"]?.toBoolean() ?: false
        call.respond(refusal.draft(actId, auth.userId, rebuild))
    }

    get("/api/acts/{id}/refusal") {
        val auth = call.authUser(cfg)
        val actId = call.parameters["id"]!!.toLong()
        call.respond(refusal.get(actId, auth.userId))
    }

    put("/api/acts/{id}/refusal") {
        val auth = call.authUser(cfg)
        val actId = call.parameters["id"]!!.toLong()
        val input = call.receive<RefusalEdit>()
        call.respond(refusal.edit(actId, auth.userId, input))
    }

    post("/api/acts/{id}/refusal/confirm") {
        val auth = call.authUser(cfg)
        val actId = call.parameters["id"]!!.toLong()
        call.respond(refusal.confirm(actId, auth.userId))
    }

    get("/api/me") {
        val auth = call.authUser(cfg)
        val dto = tx {
            val user = Users.selectAll().where { Users.id eq auth.userId }.singleOrNull()
            val roles = rolesOfTx(auth.userId)
            val houseAddress = roles.houseId?.let {
                Houses.select(Houses.address).where { Houses.id eq it }.singleOrNull()?.get(Houses.address)
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
                startParam = auth.startParam,
            )
        }
        call.respond(dto)
    }
}
