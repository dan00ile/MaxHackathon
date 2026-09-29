package mkd

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Подписи нет (Госключ не подключён), поэтому председателю возвращается ровно загруженный им файл.
// Тест держит это свойство: подписанный экземпляр не генерируется, а отдаётся байт в байт исходный,
// в том числе когда акт пришёл фотографией, а не PDF.
class SignReturnsOriginalTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val chairmanId = 500L
    private val filesDir = "build/tmp/test-files-sign"

    private var houseRef = EntityID(0L, Houses)

    private fun testConfig() = Config(
        port = 0, dbUrl = "", dbUser = "", dbPassword = "",
        maxToken = "", maxApiBase = "http://127.0.0.1:1",
        gigaAuthKey = "", gigaScope = "", gigaModel = "",
        corsOrigin = "", adminUserIds = emptySet(), demoMode = true, devAuth = false,
        filesDir = filesDir, zone = zone, publicUrl = "", webhookSecret = "",
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
            val houseId = (Houses.insert {
                it[Houses.address] = "г. Казань, ул. Демонстрационная, д. 1"
                it[Houses.ukId] = ukRef
            } get Houses.id).value
            houseRef = EntityID(houseId, Houses)
            Users.insert {
                it[Users.id] = chairmanId
                it[Users.name] = "Председатель"
                it[Users.houseId] = houseRef
                it[Users.pdConsentAt] = Instant.now()
                it[Users.createdAt] = Instant.now()
            }
            Chairmen.insert {
                it[Chairmen.userId] = chairmanId
                it[Chairmen.houseId] = houseRef
                it[Chairmen.fullName] = "Сидоров С. С."
                it[Chairmen.authorityBasis] = "протокол ОСС № 1"
                it[Chairmen.confirmedAt] = Instant.now()
            }
        }
    }

    private fun insertAct(fileName: String, content: ByteArray): Pair<Long, Path> {
        val dir = Path.of(filesDir, "acts")
        Files.createDirectories(dir)
        val stored = dir.resolve("original-$fileName")
        Files.write(stored, content)
        val actId = transaction(testDb) {
            (Acts.insert {
                it[Acts.houseId] = houseRef
                it[Acts.number] = "12"
                it[Acts.period] = "сентябрь 2026"
                it[Acts.formedDate] = LocalDate.of(2026, 9, 30)
                it[Acts.receivedAt] = Instant.now()
                it[Acts.deadline10] = LocalDate.of(2026, 10, 10)
                it[Acts.deadline30] = LocalDate.of(2026, 10, 30)
                it[Acts.status] = ActStatus.REVIEW
                it[Acts.filePath] = stored.toString()
                it[Acts.fileName] = fileName
                it[Acts.uploadedBy] = chairmanId
                it[Acts.recognition] = Recognition.DONE
                it[Acts.createdAt] = Instant.now()
            } get Acts.id).value
        }
        return actId to stored
    }

    // Отправка в MAX в тестах недоступна; она идёт после фиксации решения, поэтому проверяем состояние в БД
    private fun signIgnoringDelivery(actId: Long) {
        runBlocking { runCatching { actService().sign(actId, chairmanId) } }
    }

    @Test
    fun `signed copy is the uploaded file itself`() {
        val content = "%PDF-1.4 исходный акт от УК".toByteArray()
        val (actId, stored) = insertAct("akt-sentyabr.pdf", content)

        signIgnoringDelivery(actId)

        val act = transaction(testDb) { Acts.selectAll().where { Acts.id eq actId }.single() }
        assertEquals(ActStatus.SIGNED, act[Acts.status])
        assertEquals(stored.toString(), act[Acts.signedPdfPath], "отдавать надо исходный файл, а не сгенерированный")
        assertContentEquals(content, Files.readAllBytes(stored), "исходный файл не должен меняться")
    }

    // Акт могут прислать фотографией — тогда «подписанный экземпляр» тем более не PDF, который мы рисуем
    @Test
    fun `photo act is returned as the same photo`() {
        val content = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02)
        val (actId, stored) = insertAct("akt-foto.jpg", content)

        signIgnoringDelivery(actId)

        val act = transaction(testDb) { Acts.selectAll().where { Acts.id eq actId }.single() }
        assertEquals(stored.toString(), act[Acts.signedPdfPath])
        assertContentEquals(content, Files.readAllBytes(stored))
    }

    @Test
    fun `no document is generated on signing`() {
        val (actId, _) = insertAct("akt.pdf", "исходник".toByteArray())

        signIgnoringDelivery(actId)

        val generated = Path.of(filesDir, "pdf")
        val leftovers = if (Files.isDirectory(generated)) {
            Files.list(generated).use { s -> s.map { it.fileName.toString() }.filter { "act-$actId" in it }.toList() }
        } else {
            emptyList()
        }
        assertTrue(leftovers.isEmpty(), "при подписании не должно появляться сгенерированных файлов: $leftovers")
    }
}
