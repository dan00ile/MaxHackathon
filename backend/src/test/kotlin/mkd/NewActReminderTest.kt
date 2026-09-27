package mkd

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// После отказа бот один раз напоминает председателю о новом акте — если УК его так и не прислала
class NewActReminderTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val chairmanId = 100L
    private val refusalSentAt = Instant.parse("2026-09-01T09:00:00Z")
    private val beforeDue = refusalSentAt.plus(29, ChronoUnit.DAYS)
    private val afterDue = refusalSentAt.plus(31, ChronoUnit.DAYS)
    private var houseRowId = 0L
    private var rejectedActId = 0L

    @BeforeTest
    fun setUp() {
        transaction(testDb) { seed() }
    }

    private fun seed() {
        allTables.reversed().forEach { it.deleteAll() }
        val ukRef = ManagementCompanies.insert {
            it[ManagementCompanies.name] = "ООО «УК Тест»"
            it[ManagementCompanies.inn] = "0000000000"
            it[ManagementCompanies.licenseNo] = "№ 1"
            it[ManagementCompanies.representative] = "Директор Петров П. П."
            it[ManagementCompanies.exchangeMethod] = "email: uk@test.ru"
        } get ManagementCompanies.id
        houseRowId = (Houses.insert {
            it[Houses.address] = "г. Казань, ул. Тестовая, д. 1"
            it[Houses.ukId] = ukRef
        } get Houses.id).value
        Users.insert {
            it[Users.id] = chairmanId
            it[Users.name] = "Председатель"
            it[Users.houseId] = EntityID(houseRowId, Houses)
            it[Users.createdAt] = refusalSentAt
        }
        rejectedActId = insertAct(ActStatus.REJECTED, refusalSentAt.minus(20, ChronoUnit.DAYS))
        Refusals.insert {
            it[Refusals.actId] = EntityID(rejectedActId, Acts)
            it[Refusals.draftJson] = "{}"
            it[Refusals.place] = "г. Казань"
            it[Refusals.confirmedAt] = refusalSentAt
            it[Refusals.sentAt] = refusalSentAt
            it[Refusals.createdAt] = refusalSentAt
        }
    }

    private fun insertAct(status: ActStatus, createdAt: Instant): Long = (Acts.insert {
        it[Acts.houseId] = EntityID(houseRowId, Houses)
        it[Acts.receivedAt] = createdAt
        it[Acts.deadline10] = Deadlines.day10(createdAt, zone)
        it[Acts.deadline30] = Deadlines.day30(createdAt, zone)
        it[Acts.status] = status
        it[Acts.filePath] = "data/files/acts/act.pdf"
        it[Acts.fileName] = "act.pdf"
        it[Acts.uploadedBy] = chairmanId
        it[Acts.recognition] = Recognition.DONE
        it[Acts.createdAt] = createdAt
    } get Acts.id).value

    private fun due(now: Instant) = transaction(testDb) { dueNewActRemindersTx(now, zone).map { it[Acts.id].value } }

    @Test
    fun `reminds once a month after the refusal`() {
        assertEquals(emptyList(), due(beforeDue))
        assertEquals(listOf(rejectedActId), due(afterDue))
        transaction(testDb) { logEvent(rejectedActId, "NOTIFY_NEW_ACT", null, "sent") }
        assertEquals(emptyList(), due(afterDue))
    }

    @Test
    fun `no reminder when a new act was uploaded after the refusal`() {
        transaction(testDb) { insertAct(ActStatus.COLLECTING, refusalSentAt.plus(5, ChronoUnit.DAYS)) }
        assertEquals(emptyList(), due(afterDue))
    }

    @Test
    fun `rejected act itself does not count as a new act`() {
        transaction(testDb) {
            Acts.update({ Acts.id eq rejectedActId }) { it[Acts.createdAt] = refusalSentAt.plus(1, ChronoUnit.DAYS) }
        }
        assertEquals(listOf(rejectedActId), due(afterDue))
    }
}
