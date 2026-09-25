package mkd

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll

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

fun Route.api(cfg: Config) {
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
