package mkd

import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class RemarkService(private val cfg: Config, private val acts: ActService) {

    private suspend fun itemActId(itemId: Long): Long = tx {
        ActItems.select(ActItems.actId).where { ActItems.id eq itemId }.singleOrNull()
            ?.get(ActItems.actId)?.value ?: throw ApiError(HttpStatusCode.NotFound, "not_found", "Позиция не найдена")
    }

    suspend fun saveMy(itemId: Long, userId: Long, verdict: Verdict, text: String?): MyRemarkDto {
        if (verdict == Verdict.ISSUE && ((text?.trim()?.length ?: 0) !in 3..1000)) {
            throw ApiError(HttpStatusCode.BadRequest, "invalid_text", "Опишите замечание текстом от 3 до 1000 символов")
        }
        val trimmedText = if (verdict == Verdict.ISSUE) text!!.trim() else null
        val actId = itemActId(itemId)
        acts.requireMemberOf(actId, userId)
        val remarkId = tx {
            val status = Acts.select(Acts.status).where { Acts.id eq actId }.single()[Acts.status]
            if (status != ActStatus.COLLECTING) {
                throw ApiError(HttpStatusCode.Conflict, "collection_closed", "Сбор замечаний закрыт")
            }
            val existing = Remarks.selectAll().where { (Remarks.itemId eq itemId) and (Remarks.authorId eq userId) }.singleOrNull()
            val now = Instant.now()
            val id: Long = if (existing != null) {
                val rid = existing[Remarks.id].value
                Remarks.update({ Remarks.id eq rid }) {
                    it[Remarks.verdict] = verdict
                    it[originalText] = trimmedText
                    it[updatedAt] = now
                }
                rid
            } else {
                (Remarks.insert {
                    it[Remarks.itemId] = EntityID(itemId, ActItems)
                    it[authorId] = userId
                    it[Remarks.verdict] = verdict
                    it[originalText] = trimmedText
                    it[createdAt] = now
                    it[updatedAt] = now
                } get Remarks.id).value
            }
            if (verdict == Verdict.OK) Attachments.deleteWhere { Op.build { Attachments.remarkId eq id } }
            logEvent(actId, "REMARK_SAVED", userId, "itemId=$itemId; verdict=$verdict")
            id
        }
        val photos = tx { photosOfTx(remarkId) }
        return MyRemarkDto(verdict.name, trimmedText, photos)
    }

    suspend fun addPhoto(itemId: Long, userId: Long, bytes: ByteArray, mime: String): PhotoDto {
        val actId = itemActId(itemId)
        acts.requireMemberOf(actId, userId)
        val ext = if (mime == "image/png") "png" else "jpg"
        val dir = Path.of(cfg.filesDir, "photos")
        Files.createDirectories(dir)
        val storedPath = dir.resolve("${UUID.randomUUID()}.$ext")
        return tx {
            val remark = Remarks.selectAll().where { (Remarks.itemId eq itemId) and (Remarks.authorId eq userId) }.singleOrNull()
            if (remark == null || remark[Remarks.verdict] != Verdict.ISSUE) {
                throw ApiError(HttpStatusCode.Conflict, "no_issue", "Сначала сохраните замечание с текстом претензии")
            }
            val remarkId = remark[Remarks.id].value
            val count = Attachments.selectAll().where { Attachments.remarkId eq remarkId }.count()
            if (count >= 5) throw ApiError(HttpStatusCode.Conflict, "too_many_photos", "Не более 5 фото на замечание")
            Files.write(storedPath, bytes)
            val id = Attachments.insert {
                it[Attachments.remarkId] = EntityID(remarkId, Remarks)
                it[filePath] = storedPath.toString()
                it[Attachments.mime] = mime
                it[authorId] = userId
                it[uploadedAt] = Instant.now()
            } get Attachments.id
            logEvent(actId, "PHOTO_ADDED", userId, "itemId=$itemId")
            PhotoDto(id.value, "/api/photos/${id.value}")
        }
    }

    suspend fun photoFile(photoId: Long, userId: Long): Pair<File, String> = tx {
        val row = Attachments.selectAll().where { Attachments.id eq photoId }.singleOrNull()
            ?: throw ApiError(HttpStatusCode.NotFound, "not_found", "Фото не найдено")
        if (row[Attachments.authorId] != userId) {
            val remarkItemId = Remarks.select(Remarks.itemId).where { Remarks.id eq row[Attachments.remarkId] }.single()[Remarks.itemId].value
            val actId = ActItems.select(ActItems.actId).where { ActItems.id eq remarkItemId }.single()[ActItems.actId].value
            val houseId = Acts.select(Acts.houseId).where { Acts.id eq actId }.single()[Acts.houseId].value
            val roles = rolesOfTx(userId)
            if (!(roles.chairman && roles.houseId == houseId)) {
                throw ApiError(HttpStatusCode.Forbidden, "forbidden", "Доступ запрещён")
            }
        }
        File(row[Attachments.filePath]) to row[Attachments.mime]
    }
}
