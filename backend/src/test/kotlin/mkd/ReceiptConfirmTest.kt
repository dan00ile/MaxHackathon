package mkd

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Сообщение с вопросом «Когда вы получили акт?» остаётся в чате — повторное нажатие «Сегодня»
// или ввод другой даты не должны ни сдвигать T0, ни заново запускать распознавание
class ReceiptConfirmTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val chairmanId = 100L
    private val uploadedAt = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    private var actId = 0L

    @BeforeTest
    fun setUp() = transaction(testDb) {
        allTables.reversed().forEach { it.deleteAll() }
        val ukRef = ManagementCompanies.insert {
            it[ManagementCompanies.name] = "ООО «УК Тест»"
            it[ManagementCompanies.inn] = "0000000000"
            it[ManagementCompanies.licenseNo] = "№ 1"
            it[ManagementCompanies.representative] = "Директор Петров П. П."
            it[ManagementCompanies.exchangeMethod] = "email: uk@test.ru"
        } get ManagementCompanies.id
        val houseRef = Houses.insert {
            it[Houses.address] = "г. Казань, ул. Тестовая, д. 1"
            it[Houses.ukId] = ukRef
        } get Houses.id
        Users.insert {
            it[Users.id] = chairmanId
            it[Users.name] = "Председатель"
            it[Users.houseId] = houseRef
            it[Users.pdConsentAt] = Instant.now()
            it[Users.createdAt] = Instant.now()
        }
        actId = (Acts.insert {
            it[Acts.houseId] = EntityID(houseRef.value, Houses)
            it[Acts.receivedAt] = uploadedAt
            it[Acts.deadline10] = Deadlines.day10(uploadedAt, zone)
            it[Acts.deadline30] = Deadlines.day30(uploadedAt, zone)
            it[Acts.status] = ActStatus.RECEIVED
            it[Acts.filePath] = "data/files/acts/act.pdf"
            it[Acts.fileName] = "act.pdf"
            it[Acts.uploadedBy] = chairmanId
            it[Acts.recognition] = Recognition.PENDING
            it[Acts.createdAt] = Instant.now()
        } get Acts.id).value
    }

    private fun receivedAt() = transaction(testDb) {
        Acts.selectAll().where { Acts.id eq actId }.single()[Acts.receivedAt]
    }

    private fun confirmedEvents() = transaction(testDb) {
        Events.selectAll().where { (Events.actId eq actId) and (Events.type eq "RECEIPT_CONFIRMED") }.count()
    }

    @Test
    fun `receipt is confirmed once and T0 does not move afterwards`() {
        transaction(testDb) { confirmReceiptTx(actId, chairmanId, null, zone) }
        assertEquals(uploadedAt, receivedAt(), "«Сегодня» оставляет T0 моментом загрузки")

        assertFailsWith<ApiError>("повторное «Сегодня»") {
            transaction(testDb) { confirmReceiptTx(actId, chairmanId, null, zone) }
        }
        val otherDate = Instant.now().minus(10, ChronoUnit.DAYS)
        val error = assertFailsWith<ApiError>("другая дата после подтверждения") {
            transaction(testDb) { confirmReceiptTx(actId, chairmanId, otherDate, zone) }
        }
        assertEquals("receipt_confirmed", error.code)
        assertEquals(uploadedAt, receivedAt(), "T0 не сдвинулся")
        assertEquals(1, confirmedEvents())
    }

    @Test
    fun `other date sets T0 and deadlines`() {
        val otherDate = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        transaction(testDb) { confirmReceiptTx(actId, chairmanId, otherDate, zone) }
        val act = transaction(testDb) { Acts.selectAll().where { Acts.id eq actId }.single() }
        assertEquals(otherDate, act[Acts.receivedAt])
        assertEquals(Deadlines.day30(otherDate, zone), act[Acts.deadline30])
    }

    @Test
    fun `act already in work counts as confirmed`() {
        // акты, заведённые до события RECEIPT_CONFIRMED: подписанный акт не должен получить новую дату
        transaction(testDb) { Acts.update({ Acts.id eq actId }) { it[status] = ActStatus.SIGNED } }
        assertFailsWith<ApiError> { transaction(testDb) { lockOpenReceiptTx(actId, zone) } }
        assertEquals(0, confirmedEvents())
    }
}
