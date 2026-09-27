package mkd

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Диалог «Другая дата» целиком: настоящий Postgres и фейковый MAX API, который запоминает тексты
// отправленных сообщений. Ошибка в дате не должна обрывать диалог — следующая дата принимается.
class ReceiptDateFlowTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val chairmanId = 100L
    private val uploadedAt = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    private val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val today = LocalDate.now(zone)
    private var actId = 0L

    private val sent = CopyOnWriteArrayList<String>()
    private val raw = CopyOnWriteArrayList<String>()   // все тела запросов: кнопки и ответы на нажатия
    private val maxApi = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            raw.add(body)
            if (exchange.requestURI.path == "/messages") {
                AppJson.decodeFromString(SendMessageRequest.serializer(), body).text?.let(sent::add)
            }
            val ok = "{}".toByteArray()
            exchange.sendResponseHeaders(200, ok.size.toLong())
            exchange.responseBody.use { it.write(ok) }
        }
        start()
    }

    // распознавание акта после фиксации даты здесь не нужно: в отменённом scope launch не стартует
    private val scope = CoroutineScope(Job()).apply { cancel() }
    private val bot: Bot = run {
        val cfg = Config(
            port = 0, dbUrl = "", dbUser = "", dbPassword = "",
            maxToken = "", maxApiBase = "http://127.0.0.1:${maxApi.address.port}",
            gigaAuthKey = "", gigaScope = "", gigaModel = "",
            corsOrigin = "", adminUserIds = emptySet(), demoMode = true, devAuth = false,
            filesDir = "build/tmp/test-files", zone = zone, publicUrl = "", webhookSecret = "",
        )
        val max = MaxBotClient("", cfg.maxApiBase)
        val giga = GigaChatClient("", "", "")
        val acts = ActService(cfg, max, giga, TimerService(cfg, max))
        Bot(cfg, max, acts, RefusalService(cfg, max, RemarkService(cfg, acts, giga, scope), acts), scope)
    }

    @BeforeTest
    fun setUp() {
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
            Chairmen.insert {
                it[Chairmen.userId] = chairmanId
                it[Chairmen.houseId] = houseRef
                it[Chairmen.fullName] = "Иванов Иван Иванович"
                it[Chairmen.authorityBasis] = "протокол ОСС № 5"
                it[Chairmen.confirmedAt] = Instant.now()
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
    }

    @AfterTest
    fun tearDown() = maxApi.stop(0)

    private fun press(payload: String) = runBlocking {
        bot.handle(Update("message_callback", 0, callback = Callback("cb", payload, MaxUser(chairmanId))))
    }

    private fun write(text: String) = runBlocking {
        val body = MessageBody(mid = "mid", text = text)
        bot.handle(Update("message_created", 0, message = Message(MaxUser(chairmanId), Recipient(), 0, body)))
    }

    private fun uploadAct() = runBlocking {
        val file = buildJsonObject {
            put("type", "file")
            put("filename", "act2.pdf")
            putJsonObject("payload") { put("url", "http://127.0.0.1:${maxApi.address.port}/files/act2.pdf") }
        }
        val body = MessageBody(mid = "mid2", attachments = listOf(file))
        bot.handle(Update("message_created", 0, message = Message(MaxUser(chairmanId), Recipient(), 0, body)))
    }

    private fun receivedAt(id: Long = actId) = transaction(testDb) {
        Acts.selectAll().where { Acts.id eq id }.single()[Acts.receivedAt]
    }

    private fun confirmedEvents(id: Long = actId) = transaction(testDb) {
        Events.selectAll().where { (Events.actId eq id) and (Events.type eq "RECEIPT_CONFIRMED") }.count()
    }

    @Test
    fun `expired act without a new one - drop button archives it and ends the date dialog`() {
        press("rcv_other:$actId")
        write(fmt.format(today.minusDays(40)))
        assertTrue("act_drop:$actId" in raw.last(), "к сообщению об истёкшем сроке приложена кнопка")

        write(fmt.format(today.plusDays(1)))
        assertTrue("act_drop" !in raw.last(), "при дате в будущем кнопки нет — это опечатка")

        press("act_drop:$actId")
        transaction(testDb) {
            val act = Acts.selectAll().where { Acts.id eq actId }.single()
            assertNotNull(act[Acts.archivedAt], "акт в архиве")
            assertNull(activeActTx(act[Acts.houseId].value), "активного акта в доме нет")
        }
        assertTrue(raw.any { "Акт убран в архив" in it }, "сообщение об ошибке заменено подтверждением")

        press("act_drop:$actId")
        val archivedEvents = transaction(testDb) {
            Events.selectAll().where { (Events.actId eq actId) and (Events.type eq "ACT_ARCHIVED") }.count()
        }
        assertEquals(1, archivedEvents, "повторное нажатие не архивирует второй раз")

        val before = sent.size
        write(fmt.format(today.minusDays(5)))
        assertEquals(before, sent.size, "дату больше не ждём")
        assertEquals(uploadedAt, receivedAt(), "T0 не тронут")
        assertEquals(0, confirmedEvents())
    }

    @Test
    fun `drop button does nothing once the receipt date is confirmed`() {
        press("rcv_other:$actId")
        write(fmt.format(today.minusDays(40)))
        write(fmt.format(today.minusDays(5)))

        press("act_drop:$actId")
        val archivedAt = transaction(testDb) { Acts.selectAll().where { Acts.id eq actId }.single()[Acts.archivedAt] }
        assertNull(archivedAt, "акт в работе не архивируем")
        assertTrue(raw.last().contains("уже зафиксирована"), raw.last())
    }

    @Test
    fun `expired act - uploading another act ends the date dialog of the old one`() {
        press("rcv_other:$actId")
        write(fmt.format(today.minusDays(40)))
        assertTrue("загрузите его файлом" in sent.last(), sent.last())

        uploadAct()
        val newActId = transaction(testDb) { Acts.selectAll().maxOf { it[Acts.id].value } }
        assertTrue(newActId != actId)
        assertTrue("Когда вы получили этот акт" in sent.last(), sent.last())

        press("rcv_today:$newActId")
        assertEquals(1, confirmedEvents(newActId), "новый акт принят с датой загрузки")
        val before = sent.size

        write(fmt.format(today.minusDays(5)))
        assertEquals(before, sent.size, "текст не разбирается как дата старого акта")
        assertEquals(uploadedAt, receivedAt(), "T0 старого акта не тронут")
        assertEquals(0, confirmedEvents())
    }

    @Test
    fun `date outside the window keeps the dialog and the next date is accepted`() {
        press("rcv_other:$actId")

        write(fmt.format(today.minusDays(40)))
        assertTrue("Срок по этому акту истёк" in sent.last(), sent.last())
        assertEquals(uploadedAt, receivedAt(), "T0 не тронут")
        assertEquals(0, confirmedEvents())

        write(fmt.format(today.plusDays(1)))
        assertTrue("позже сегодняшней" in sent.last(), sent.last())

        val good = today.minusDays(5)
        write(fmt.format(good))
        assertEquals(good.atTime(12, 0).atZone(zone).toInstant(), receivedAt(), "T0 — введённая дата")
        assertEquals(1, confirmedEvents())
    }

    @Test
    fun `pressing today after a wrong date ends the date dialog`() {
        press("rcv_other:$actId")
        write(fmt.format(today.minusDays(40)))

        press("rcv_today:$actId")
        assertEquals(uploadedAt, receivedAt(), "«Сегодня» — T0 момент загрузки")
        val before = sent.size

        write(fmt.format(today.minusDays(5)))
        assertEquals(before, sent.size, "дату больше не ждём — текст не разбирается как дата")
        assertEquals(uploadedAt, receivedAt())
        assertEquals(1, confirmedEvents())
    }
}
