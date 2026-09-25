package mkd

import io.ktor.http.HttpStatusCode
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
import java.time.temporal.ChronoUnit
import java.util.UUID

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

class ActService(private val cfg: Config, private val max: MaxBotClient) {

    suspend fun activeAct(houseId: Long): ResultRow? = tx {
        Acts.selectAll()
            .where { (Acts.houseId eq houseId) and (Acts.status inList listOf(ActStatus.RECEIVED, ActStatus.COLLECTING, ActStatus.REVIEW)) }
            .orderBy(Acts.createdAt to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
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

    // на этом шаге — заглушка; реальное распознавание через GigaChat см. S10
    suspend fun recognize(actId: Long) {
        tx {
            Acts.update({ Acts.id eq actId }) { it[recognition] = Recognition.FAILED }
            logEvent(actId, "RECOGNITION_FAILED", null)
        }
    }
}
