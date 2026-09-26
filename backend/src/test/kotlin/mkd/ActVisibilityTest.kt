package mkd

import io.ktor.http.*
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
import kotlin.test.assertFailsWith

// «Какой акт видит пользователь» решает БД, поэтому проверяем на настоящей схеме (стенд — TestDb.kt).
// Регресс, из-за которого тест появился: бот показывал подписанный акт через запасную выборку,
// а мини-апп знал только про активные — и писал «активного акта нет» на тот же дом.
class ActVisibilityTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val residentId = 300L

    private var houseId = 0L
    private var houseRef = EntityID(0L, Houses)

    private fun testConfig() = Config(
        port = 0, dbUrl = "", dbUser = "", dbPassword = "",
        maxToken = "", maxApiBase = "http://127.0.0.1:1",
        gigaAuthKey = "", gigaScope = "", gigaModel = "",
        corsOrigin = "", adminUserIds = emptySet(), demoMode = true, devAuth = false,
        filesDir = "build/tmp/test-files", zone = zone, publicUrl = "", webhookSecret = "",
    )

    private fun actService(): ActService {
        val cfg = testConfig()
        val max = MaxBotClient("", cfg.maxApiBase)
        return ActService(cfg, max, GigaChatClient("", "", ""), TimerService(cfg, max))
    }

    @BeforeTest
    fun setUp() {
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
                it[Houses.address] = "г. Казань, ул. Демонстрационная, д. 1"
                it[Houses.ukId] = ukRef
            } get Houses.id).value
            houseRef = EntityID(houseId, Houses)

            Users.insert {
                it[Users.id] = residentId
                it[Users.name] = "Житель"
                it[Users.houseId] = houseRef
                it[Users.pdConsentAt] = Instant.now()
                it[Users.createdAt] = Instant.now()
            }
        }
    }

    // daysAgo — и дата получения, и порядок создания: последним считается самый свежий акт
    private fun insertAct(status: ActStatus, daysAgo: Long, archived: Boolean = false): Long =
        transaction(testDb) {
            val receivedAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS)
            (Acts.insert {
                it[Acts.houseId] = houseRef
                it[Acts.number] = "$daysAgo"
                it[Acts.receivedAt] = receivedAt
                it[Acts.deadline10] = Deadlines.day10(receivedAt, zone)
                it[Acts.deadline30] = Deadlines.day30(receivedAt, zone)
                it[Acts.status] = status
                it[Acts.filePath] = "data/files/acts/act.pdf"
                it[Acts.fileName] = "act.pdf"
                it[Acts.uploadedBy] = residentId
                it[Acts.recognition] = Recognition.DONE
                it[Acts.createdAt] = receivedAt
                it[Acts.archivedAt] = if (archived) Instant.now() else null
            } get Acts.id).value
        }

    private fun houseActIds() = transaction(testDb) { houseActsTx(houseId).map { it[Acts.id].value } }

    // в доме может одновременно идти проверка нескольких актов — меню бота и список в мини-аппе видят все
    @Test
    fun `house lists every act, newest first`() {
        val signed = insertAct(ActStatus.SIGNED, daysAgo = 40)
        val collecting = insertAct(ActStatus.COLLECTING, daysAgo = 5)
        val received = insertAct(ActStatus.RECEIVED, daysAgo = 1)

        assertEquals(listOf(received, collecting, signed), houseActIds())
    }

    @Test
    fun `archived act is invisible`() {
        val visible = insertAct(ActStatus.COLLECTING, daysAgo = 3)
        insertAct(ActStatus.SIGNED, daysAgo = 5, archived = true)

        assertEquals(listOf(visible), houseActIds(), "архивный акт не показываем ни в боте, ни в мини-аппе")
    }

    @Test
    fun `old deep link to an archived act does not open it`() {
        val actId = insertAct(ActStatus.COLLECTING, daysAgo = 3, archived = true)

        val error = assertFailsWith<ApiError> { runBlocking { actService().requireMemberOf(actId, residentId) } }
        assertEquals(HttpStatusCode.NotFound, error.status)
    }

    @Test
    fun `resident of the house opens a finished act`() {
        val actId = insertAct(ActStatus.SIGNED, daysAgo = 5)

        val act = runBlocking { actService().requireMemberOf(actId, residentId) }
        assertEquals(actId, act[Acts.id].value)
    }
}
