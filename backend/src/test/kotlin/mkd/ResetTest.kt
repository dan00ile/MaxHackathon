package mkd

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Сброс — это целиком про состояние БД, поэтому проверяем на настоящей схеме и настоящем Postgres
// (стенд — в TestDb.kt). Внутри insert/update неявный receiver — сама таблица, поэтому ссылки на
// строки заведены отдельными *Ref-переменными: голое houseId там резолвилось бы в колонку,
// а не в наше значение.
class ResetTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val chairmanId = 100L
    private val residentId = 200L

    private var houseId = 0L
    private var actId = 0L
    private var itemId = 0L
    private var remarkId = 0L

    private fun testConfig() = Config(
        port = 0, dbUrl = "", dbUser = "", dbPassword = "",
        maxToken = "", maxApiBase = "http://127.0.0.1:1",
        gigaAuthKey = "", gigaScope = "", gigaModel = "",
        corsOrigin = "", adminUserIds = emptySet(), demoMode = true, devAuth = false,
        filesDir = "build/tmp/test-files", zone = zone, publicUrl = "", webhookSecret = "",
    )

    @BeforeTest
    fun setUp() {
        // в проде заполняется на старте из max.me(); нужен для ссылок на мини-апп в уведомлениях
        botUsername = "mkd_test_bot"
        transaction(testDb) {
            allTables.reversed().forEach { it.deleteAll() }

            val ukRef = ManagementCompanies.insert {
                it[ManagementCompanies.name] = "ООО «УК Тест»"
                it[ManagementCompanies.inn] = "0000000000"
                it[ManagementCompanies.licenseNo] = "№ 1"
                it[ManagementCompanies.representative] = "Директор Петров П. П."
                it[ManagementCompanies.exchangeMethod] = "email: uk@test.ru"
            } get ManagementCompanies.id
            houseId = (Houses.insert {
                it[Houses.address] = "г. Казань, ул. Тестовая, д. 1"
                it[Houses.ukId] = ukRef
            } get Houses.id).value
            val houseRef = EntityID(houseId, Houses)

            listOf(chairmanId to "Председатель", residentId to "Житель").forEach { (maxUserId, userName) ->
                Users.insert {
                    it[Users.id] = maxUserId
                    it[Users.name] = userName
                    it[Users.houseId] = houseRef
                    it[Users.pdConsentAt] = Instant.now()
                    it[Users.createdAt] = Instant.now()
                }
            }
            Chairmen.insert {
                it[Chairmen.userId] = chairmanId
                it[Chairmen.houseId] = houseRef
                it[Chairmen.fullName] = "Иванов Иван Иванович"
                it[Chairmen.authorityBasis] = "протокол ОСС № 5"
                it[Chairmen.confirmedAt] = Instant.now()
            }

            val actReceivedAt = Instant.now().minus(3, ChronoUnit.DAYS)
            actId = (Acts.insert {
                it[Acts.houseId] = houseRef
                it[Acts.number] = "17"
                it[Acts.receivedAt] = actReceivedAt
                it[Acts.deadline10] = Deadlines.day10(actReceivedAt, zone)
                it[Acts.deadline30] = Deadlines.day30(actReceivedAt, zone)
                it[Acts.status] = ActStatus.COLLECTING
                it[Acts.filePath] = "data/files/acts/act.pdf"
                it[Acts.fileName] = "act.pdf"
                it[Acts.uploadedBy] = chairmanId
                it[Acts.recognition] = Recognition.DONE
                it[Acts.createdAt] = Instant.now()
            } get Acts.id).value
            val actRef = EntityID(actId, Acts)

            itemId = (ActItems.insert {
                it[ActItems.actId] = actRef
                it[ActItems.lineNo] = 1
                it[ActItems.name] = "Уборка лестничных клеток"
            } get ActItems.id).value
            val itemRef = EntityID(itemId, ActItems)

            remarkId = (Remarks.insert {
                it[Remarks.itemId] = itemRef
                it[Remarks.authorId] = residentId
                it[Remarks.verdict] = Verdict.ISSUE
                it[Remarks.originalText] = "Не мыли неделю"
                it[Remarks.formalizedText] = "Влажная уборка лестничных клеток не выполнена"
                it[Remarks.llmStatus] = LlmStatus.DONE
                it[Remarks.createdAt] = Instant.now()
                it[Remarks.updatedAt] = Instant.now()
            } get Remarks.id).value
            val remarkRef = EntityID(remarkId, Remarks)

            Attachments.insert {
                it[Attachments.remarkId] = remarkRef
                it[Attachments.filePath] = "data/files/photos/photo.jpg"
                it[Attachments.mime] = "image/jpeg"
                it[Attachments.authorId] = residentId
                it[Attachments.uploadedAt] = Instant.now()
            }
            Refusals.insert {
                it[Refusals.actId] = actRef
                it[Refusals.draftJson] = "{\"objections\":[],\"noObjectionLineNos\":[1]}"
                it[Refusals.place] = "г. Казань, ул. Тестовая, д. 1"
                it[Refusals.createdAt] = Instant.now()
            }
        }
    }

    private fun makeActOverdue() = transaction(testDb) {
        val overdue = Instant.now().minus(40, ChronoUnit.DAYS)
        Acts.update({ Acts.id eq actId }) {
            it[Acts.receivedAt] = overdue
            it[Acts.deadline10] = Deadlines.day10(overdue, zone)
            it[Acts.deadline30] = Deadlines.day30(overdue, zone)
        }
    }

    @Test
    fun `chairman reset archives act and deletes nothing`() {
        val result = runBlocking { resetUser(chairmanId) }

        assertEquals(listOf(actId), result.archivedActIds)
        assertEquals(houseId, result.houseId)
        assertTrue(result.wasChairman)

        transaction(testDb) {
            val act = Acts.selectAll().where { Acts.id eq actId }.single()
            assertNotNull(act[Acts.archivedAt], "акт должен быть помечен архивным")
            assertEquals(ActStatus.COLLECTING, act[Acts.status], "статус по приказу сбросом не меняется")
            assertNull(activeActTx(houseId), "активного акта в доме больше нет")

            assertEquals(1, ActItems.selectAll().count(), "позиции акта сохранены")
            assertEquals(1, Remarks.selectAll().count(), "замечания жителей сохранены")
            assertEquals(1, Attachments.selectAll().count(), "фото сохранены")
            assertEquals(1, Refusals.selectAll().count(), "черновик отказа сохранён")

            val user = Users.selectAll().where { Users.id eq chairmanId }.single()
            assertNull(user[Users.houseId], "пользователь отвязан от дома")
            assertNull(user[Users.pdConsentAt], "согласие снято — онбординг пройдёт заново")
            assertEquals("Председатель", user[Users.name], "сам пользователь не удалён")
            assertEquals(0, Chairmen.selectAll().where { Chairmen.userId eq chairmanId }.count())

            val events = Events.selectAll().map { it[Events.type] }
            assertTrue("ACT_ARCHIVED" in events)
            assertTrue("USER_RESET" in events)
        }
    }

    @Test
    fun `resident reset keeps his remarks and leaves the act active`() {
        runBlocking { resetUser(residentId) }

        transaction(testDb) {
            val act = Acts.selectAll().where { Acts.id eq actId }.single()
            assertNull(act[Acts.archivedAt], "житель не архивирует чужой акт")
            assertNotNull(activeActTx(houseId), "акт остаётся активным для остальных")

            val remark = Remarks.selectAll().where { Remarks.id eq remarkId }.single()
            assertEquals(residentId, remark[Remarks.authorId], "авторство замечания сохранено")
            assertEquals(1, Attachments.selectAll().count())

            val user = Users.selectAll().where { Users.id eq residentId }.single()
            assertNull(user[Users.houseId])
            assertNull(user[Users.pdConsentAt])
        }
    }

    @Test
    fun `chairman can register for the same house again after reset`() {
        runBlocking { resetUser(chairmanId) }

        transaction(testDb) {
            val houseRef = EntityID(houseId, Houses)
            Users.update({ Users.id eq chairmanId }) { it[Users.houseId] = houseRef }
            Chairmen.insert {
                it[Chairmen.userId] = chairmanId
                it[Chairmen.houseId] = houseRef
                it[Chairmen.fullName] = "Иванов Иван Иванович"
                it[Chairmen.authorityBasis] = "протокол ОСС № 6"
                it[Chairmen.confirmedAt] = Instant.now()
            }
            assertEquals(1, Chairmen.selectAll().where { Chairmen.userId eq chairmanId }.count())
        }
    }

    @Test
    fun `archived act does not slide into silent consent`() {
        makeActOverdue()
        runBlocking { resetUser(chairmanId) }

        runBlocking { TimerService(testConfig(), MaxBotClient("", "http://127.0.0.1:1")).tick() }

        transaction(testDb) {
            val act = Acts.selectAll().where { Acts.id eq actId }.single()
            assertEquals(ActStatus.COLLECTING, act[Acts.status], "архивный акт таймерами не трогается")
            val events = Events.selectAll().map { it[Events.type] }
            assertTrue("SILENT_CONSENT" !in events)
            assertTrue(events.none { it.startsWith("NOTIFY_") }, "по архивному акту уведомления не шлём")
        }
    }

    // на уже поднятом стенде таблица acts создана без archived_at: проверяем, что Db.init
    // доливает колонку, а не падает и не требует ручного ALTER
    @Test
    fun `migration adds archived_at to a table created before the change`() {
        transaction(testDb) { exec("ALTER TABLE acts DROP COLUMN archived_at") }

        transaction(testDb) { SchemaUtils.createMissingTablesAndColumns(*allTables) }

        transaction(testDb) {
            val act = Acts.selectAll().where { Acts.id eq actId }.single()
            assertNull(act[Acts.archivedAt], "колонка добавлена и пуста для старых актов")
            assertNotNull(activeActTx(houseId), "выборка активного акта работает после миграции")
        }
    }

    @Test
    fun `overdue act without reset still slides into silent consent`() {
        makeActOverdue()

        runBlocking { TimerService(testConfig(), MaxBotClient("", "http://127.0.0.1:1")).tick() }

        transaction(testDb) {
            val act = Acts.selectAll().where { Acts.id eq actId }.single()
            assertEquals(ActStatus.SILENT, act[Acts.status], "обычный просроченный акт уходит в молчаливое согласие")
            assertTrue("SILENT_CONSENT" in Events.selectAll().map { it[Events.type] })
        }
    }
}
