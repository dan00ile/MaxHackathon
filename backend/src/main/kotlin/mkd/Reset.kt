package mkd

import org.jetbrains.exposed.sql.*
import java.time.Instant

data class ResetResult(val archivedActIds: List<Long>, val houseId: Long?, val wasChairman: Boolean)

// Демо-сброс: пользователь выходит из дома и проходит регистрацию заново. Ничего не удаляется —
// акты уходят в архив, а замечания, фото, отказы и журнал событий остаются на месте.
suspend fun resetUser(userId: Long): ResetResult = tx {
    val houseId = Users.select(Users.houseId).where { Users.id eq userId }
        .singleOrNull()?.get(Users.houseId)?.value
    val now = Instant.now()

    // акты, которые загрузил этот пользователь и которые ещё в работе, — в архив
    val archivedActIds = Acts.select(Acts.id)
        .where { (Acts.uploadedBy eq userId) and (Acts.status inList ACTIVE_STATUSES) and Acts.archivedAt.isNull() }
        .map { it[Acts.id].value }
    archivedActIds.forEach { actId ->
        Acts.update({ Acts.id eq actId }) { it[archivedAt] = now }
        logEvent(actId, "ACT_ARCHIVED", userId, "reason=user_reset")
    }

    // строку председателя удаляем, а не архивируем: на неё не ссылается ни один документ
    // (ФИО попадает в PDF при генерации), а uniqueIndex(userId, houseId) не даст
    // зарегистрироваться председателем повторно, если оставить её на месте
    val wasChairman = Chairmen.deleteWhere { Op.build { Chairmen.userId eq userId } } > 0

    // сам пользователь остаётся: он автор своих замечаний в уже собранных документах
    Users.update({ Users.id eq userId }) {
        it[Users.houseId] = null
        it[pdConsentAt] = null
    }
    logEvent(null, "USER_RESET", userId, "houseId=$houseId; archivedActs=${archivedActIds.size}")

    ResetResult(archivedActIds, houseId, wasChairman)
}
