// демо-данные: даты, объёмы, координаты рисунка и юридические формулировки — это и есть содержимое файла
@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList")

package mkd

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import javax.imageio.ImageIO
import kotlin.random.Random

private const val DEMO_HOUSE = "г. Казань, ул. Демонстрационная, д. 1"
private const val DEMO_CHAIRMAN = "Петров Пётр Петрович (демо)"

private data class GroundSeed(val workKind: String, val title: String, val legalRef: String, val wording: String)

private val GROUNDS = listOf(
    GroundSeed(
        "CLEANING", "Уборка мест общего пользования",
        "Договор управления МКД; п. 23 Минимального перечня услуг и работ, утв. постановлением Правительства РФ от 03.04.2013 № 290",
        "Работы по содержанию помещений общего имущества, включая уборку, выполняются с периодичностью, установленной договором управления",
    ),
    GroundSeed(
        "YARD", "Содержание придомовой территории",
        "Договор управления МКД; пп. 24–25 Минимального перечня № 290",
        "Работы по содержанию придомовой территории в холодный и тёплый период года выполняются с установленной периодичностью",
    ),
    GroundSeed(
        "WASTE", "Содержание контейнерных площадок",
        "Договор управления МКД; п. 26(1) Минимального перечня № 290",
        "Работы по содержанию мест накопления твёрдых коммунальных отходов выполняются в соответствии с установленными требованиями",
    ),
    GroundSeed(
        "ROOF", "Содержание и ремонт крыши",
        "Договор управления МКД; п. 7 Минимального перечня № 290",
        "Работы по надлежащему содержанию крыш выполняются в объёме, предусмотренном договором",
    ),
    GroundSeed(
        "ELEVATOR", "Содержание лифтов",
        "Договор управления МКД; п. 22 Минимального перечня № 290",
        "Работы по надлежащему содержанию лифтов выполняются в объёме, предусмотренном договором",
    ),
    GroundSeed(
        "ENGINEERING", "Внутридомовые инженерные системы",
        "Договор управления МКД; пп. 17–19 Минимального перечня № 290",
        "Работы по содержанию систем водоснабжения, отопления и водоотведения выполняются в объёме, предусмотренном договором",
    ),
    GroundSeed(
        "OTHER", "Прочие работы",
        "Договор управления МКД; ч. 2 ст. 162 ЖК РФ; Минимальный перечень № 290",
        "Услуги и работы по содержанию и ремонту общего имущества оказываются в объёме и с качеством, предусмотренными договором управления",
    ),
)

object Seed {
    fun run(cfg: Config) = transaction {
        if (!Houses.selectAll().empty()) return@transaction

        val ukCompanyId = ManagementCompanies.insert {
            it[name] = "ООО «УК Демо-Сервис»"
            it[inn] = "0000000000"
            it[licenseNo] = "№ 000-демо"
            it[address] = "420000, г. Казань, ул. Управляющая, д. 10, офис 1"
            it[representative] = "Генеральный директор Иванов И. И. (демо)"
            it[exchangeMethod] = "email: uk-demo@example.ru"
            it[isDemo] = true
        } get ManagementCompanies.id

        val houseId = Houses.insert {
            it[address] = DEMO_HOUSE
            it[ukId] = ukCompanyId
            it[hasCouncil] = true
            it[isDemo] = true
        } get Houses.id

        GROUNDS.forEach { g ->
            Grounds.insert {
                it[workKind] = g.workKind
                it[workKindTitle] = g.title
                it[legalRef] = g.legalRef
                it[wording] = g.wording
            }
        }

        if (cfg.demoMode) DemoActs(cfg, houseId.value).seed()
    }
}

// Демо-акты по дому во всех статусах — чтобы проверяющий сразу видел каждый экран, не проходя сценарий
// шесть раз. Всё помечено «(демо)»; жители — с отрицательными id, которые не совпадут с user_id в MAX,
// и без привязки к дому, поэтому бот им ничего не шлёт. Хэш файла не пишем: иначе загрузка того же
// demo-act.pdf проверяющим упрётся в «файл уже загружен»
private class DemoActs(private val cfg: Config, private val demoHouseId: Long) {
    private val now = Instant.now()
    private val files = Path.of(cfg.filesDir)
    private val actFile: Path = files.resolve("acts").resolve("demo-act.pdf").also {
        Files.createDirectories(it.parent)
        Seed::class.java.getResourceAsStream("/demo/demo-act.pdf")!!.use { src -> Files.write(it, src.readBytes()) }
    }
    private var photoNo = 0

    private data class ItemSeed(
        val name: String, val periodicity: String, val volume: String, val cost: String, val workKind: String,
    )

    private val items = listOf(
        ItemSeed("Влажная уборка лестничных площадок и маршей", "2 раза в неделю", "1 250 м²", "18 400,00", "CLEANING"),
        ItemSeed("Уборка придомовой территории", "5 дней в неделю", "3 400 м²", "22 700,00", "YARD"),
        ItemSeed("Уборка контейнерной площадки", "ежедневно", "1 площадка", "6 900,00", "WASTE"),
        ItemSeed("Осмотр и очистка кровли", "1 раз в месяц", "980 м²", "4 300,00", "ROOF"),
        ItemSeed("Техническое обслуживание лифтов", "ежемесячно", "4 лифта", "31 200,00", "ELEVATOR"),
    )

    // претензия жителя: как написал он сам и как её сформулировала LLM; theme — сюжет демо-фото
    private data class Issue(val text: String, val formalized: String, val theme: Theme, val photos: Int)

    private val issues = mapOf(
        1 to Issue(
            "В третьем подъезде полы не мыли с начала месяца, на ступенях грязь",
            "Влажная уборка лестничных площадок и маршей в подъезде № 3 не проводилась с начала отчётного " +
                "периода: на ступенях и площадках видны загрязнения.",
            Theme.STAIRS, 2,
        ),
        3 to Issue(
            "Мусор вокруг баков, не вывозили дня три",
            "Контейнерная площадка не убирается: вокруг контейнеров скопление мусора, уборка не производилась " +
                "не менее трёх дней.",
            Theme.TRASH, 1,
        ),
        4 to Issue(
            "После дождя на 9 этаже течёт с потолка",
            "После осадков на 9-м этаже наблюдается протечка с кровли: на потолке следы намокания, что " +
                "указывает на ненадлежащее содержание кровли.",
            Theme.LEAK, 1,
        ),
    )

    private val residents = listOf(
        -1L to "Анна Сергеевна (демо)", -2L to "Игорь Николаевич (демо)", -3L to "Марина Ковалёва (демо)",
    )
    private val uploaderId = -10L

    fun seed() {
        (residents + (uploaderId to "Председатель (демо)")).forEach { (id, name) ->
            Users.insert {
                it[Users.id] = id
                it[Users.name] = name
                it[pdConsentAt] = now
                it[createdAt] = now
            }
        }
        // (номер, период, дней назад получен) — свежие акты сверху списка, как в жизни
        act("10", "апрель 2026", 35, ActStatus.SILENT, issueLines = listOf(3))
        act("11", "май 2026", 16, ActStatus.REJECTED, issueLines = listOf(1, 4), dispute = listOf(1, 4))
        act("12", "июнь 2026", 12, ActStatus.SIGNED, issueLines = emptyList(), dispute = emptyList())
        act("13", "июль 2026", 8, ActStatus.REVIEW, issueLines = listOf(1, 3, 4), dispute = listOf(1, 3), undecided = 4)
        act("14", "август 2026", 3, ActStatus.COLLECTING, issueLines = listOf(1, 3))
        act("15", "сентябрь 2026", 0, ActStatus.RECEIVED, issueLines = emptyList())
    }

    // dispute == null — председатель ещё не решал; иначе оспорены эти позиции, остальные (кроме undecided) приняты
    private fun act(
        number: String, period: String, daysAgo: Long, status: ActStatus,
        issueLines: List<Int>, dispute: List<Int>? = null, undecided: Int? = null,
    ) {
        val receivedAt = now.minus(Duration.ofDays(daysAgo)).minus(Duration.ofHours(2))
        val actId = Acts.insert {
            it[Acts.houseId] = EntityID(demoHouseId, Houses)
            it[Acts.number] = number
            it[Acts.period] = period
            it[formedDate] = receivedAt.atZone(cfg.zone).toLocalDate().minusDays(2)
            it[Acts.receivedAt] = receivedAt
            it[deadline10] = Deadlines.day10(receivedAt, cfg.zone)
            it[deadline30] = Deadlines.day30(receivedAt, cfg.zone)
            it[Acts.status] = status
            it[filePath] = actFile.toString()
            it[fileName] = "akt-$number-demo.pdf"
            it[uploadedBy] = uploaderId
            it[recognition] = Recognition.DONE
            it[signedPdfPath] = if (status == ActStatus.SIGNED) actFile.toString() else null
            it[createdAt] = receivedAt
        } get Acts.id
        val id = actId.value
        event(id, "RECEIPT_CONFIRMED", receivedAt)
        // прошедшие вехи — «пропущены», иначе таймер разом пришлёт председателю все напоминания по демо-актам
        if (status in ACTIVE_STATUSES) Deadlines.MILESTONES
            .filter { now >= Deadlines.milestoneAt(receivedAt, it, cfg.zone) }
            .forEach { event(id, "NOTIFY_D$it", receivedAt, "skipped") }

        val draftItems = items.mapIndexed { idx, item ->
            val lineNo = idx + 1
            val decision = when {
                dispute == null || lineNo == undecided -> null
                lineNo in dispute -> Decision.DISPUTE
                else -> Decision.ACCEPT
            }
            val itemId = ActItems.insert {
                it[ActItems.actId] = actId
                it[ActItems.lineNo] = lineNo
                it[name] = item.name
                it[periodicity] = item.periodicity
                it[volume] = item.volume
                it[cost] = item.cost
                it[workKind] = item.workKind
                it[ActItems.decision] = decision
            } get ActItems.id
            val remarks = if (status == ActStatus.RECEIVED) emptyList()
            else remarks(itemId.value, lineNo, issueLines, receivedAt)
            DraftItem(
                itemId.value, lineNo, item.name, item.workKind, decision, remarks,
                item.periodicity, item.volume, item.cost,
            )
        }

        when (status) {
            ActStatus.SIGNED -> event(id, "SIGNED", receivedAt.plus(Duration.ofDays(4)), "stub=true; returned=original")
            ActStatus.SILENT -> {
                val deadline30 = Deadlines.day30(receivedAt, cfg.zone)
                event(id, "SILENT_CONSENT", Deadlines.silentAt(deadline30, cfg.zone))
                event(id, "NOTIFY_SILENT", Deadlines.silentAt(deadline30, cfg.zone), "skipped")
            }
            ActStatus.REJECTED -> refusal(actId, number, period, receivedAt, draftItems)
            else -> Unit
        }
    }

    // трое демо-жителей отмечают каждую позицию: по позициям из issueLines первый пишет претензию с фото
    private fun remarks(itemId: Long, lineNo: Int, issueLines: List<Int>, receivedAt: Instant): List<DraftRemark> =
        residents.mapIndexed { i, (userId, _) ->
            val at = receivedAt.plus(Duration.ofHours(6L + i * 5))
            val issue = issues[lineNo]?.takeIf { lineNo in issueLines && i == 0 }
            val remarkId = Remarks.insert {
                it[Remarks.itemId] = EntityID(itemId, ActItems)
                it[authorId] = userId
                it[verdict] = if (issue != null) Verdict.ISSUE else Verdict.OK
                it[originalText] = issue?.text
                it[formalizedText] = issue?.formalized
                it[llmStatus] = if (issue != null) LlmStatus.DONE else LlmStatus.NONE
                it[createdAt] = at
                it[updatedAt] = at
            } get Remarks.id
            repeat(issue?.photos ?: 0) {
                Attachments.insert {
                    it[Attachments.remarkId] = remarkId
                    it[filePath] = photo(issue!!.theme).toString()
                    it[mime] = "image/jpeg"
                    it[authorId] = userId
                    it[uploadedAt] = at
                }
            }
            if (issue != null) DraftRemark(Verdict.ISSUE, issue.formalized, issue.photos)
            else DraftRemark(Verdict.OK, "", 0)
        }

    // отказ собираем тем же кодом, что и в продукте: buildDraft + Pdf.refusal
    private fun refusal(
        actId: EntityID<Long>, number: String, period: String, receivedAt: Instant, items: List<DraftItem>,
    ) {
        val grounds = GROUNDS.associate { it.workKind to GroundRow(it.legalRef, it.wording) }
        val draft = buildDraft(items, grounds)
        val confirmedAt = receivedAt.plus(Duration.ofDays(5))
        val sentAt = confirmedAt.plus(Duration.ofHours(3))
        // реестр приложений: фото оспоренных позиций по порядку строк, как в RefusalService.confirm
        val photos = draft.objections.flatMap { o ->
            (Remarks innerJoin Attachments).selectAll().where { Remarks.itemId eq o.itemId }.map { o to it }
        }.mapIndexed { i, (o, row) ->
            Attachments.update({ Attachments.id eq row[Attachments.id] }) { it[registryNo] = i + 1 }
            PhotoPage(
                i + 1, o.lineNo, o.itemName, residents[0].second,
                row[Attachments.uploadedAt].atZone(cfg.zone), File(row[Attachments.filePath]),
            )
        }
        val uk = ManagementCompanies.selectAll().single()
        val bytes = Pdf.refusal(
            RefusalPdfData(
                houseAddress = DEMO_HOUSE,
                ukName = uk[ManagementCompanies.name],
                ukAddress = uk[ManagementCompanies.address],
                ukRepresentative = uk[ManagementCompanies.representative],
                exchangeMethod = uk[ManagementCompanies.exchangeMethod],
                actNumber = number,
                formedDate = receivedAt.atZone(cfg.zone).toLocalDate().minusDays(2),
                period = period,
                objections = draft.objections,
                noObjectionLineNos = draft.noObjectionLineNos,
                photos = photos,
                chairmanFio = DEMO_CHAIRMAN,
                place = DEMO_HOUSE,
                composedAt = ZonedDateTime.ofInstant(confirmedAt, cfg.zone),
                demo = true,
            ),
        )
        val pdf = files.resolve("pdf").resolve("act-${actId.value}-refusal-r1.pdf")
        Files.createDirectories(pdf.parent)
        Files.write(pdf, bytes)
        Refusals.insert {
            it[Refusals.actId] = actId
            it[draftJson] = AppJson.encodeToString(RefusalDraft.serializer(), draft)
            it[place] = DEMO_HOUSE
            it[revision] = 1
            it[Refusals.confirmedAt] = confirmedAt
            it[confirmedBy] = uploaderId
            it[pdfPath] = pdf.toString()
            it[Refusals.sentAt] = sentAt
            it[createdAt] = confirmedAt
        }
        event(actId.value, "REFUSAL_CONFIRMED", confirmedAt)
        event(actId.value, "REFUSAL_SENT", sentAt, "method=${uk[ManagementCompanies.exchangeMethod]}")
    }

    private fun event(actId: Long, type: String, at: Instant, details: String = "") {
        Events.insert {
            it[Events.actId] = actId
            it[Events.type] = type
            it[Events.at] = at
            it[Events.details] = details
        }
    }

    private fun photo(theme: Theme): Path {
        val path = files.resolve("photos").resolve("demo-${++photoNo}.jpg")
        Files.createDirectories(path.parent)
        ImageIO.write(theme.draw(Random(photoNo)), "jpg", path.toFile())
        return path
    }
}

// Демо-фото рисуем сами: своих снимков подъездов у нас нет, а чужие брать нельзя (правила хакатона, п. 7).
// Подпись «ДЕМО-ФОТО» — чтобы картинку не приняли за настоящий снимок
private enum class Theme(val caption: String, val wall: Int, val floor: Int, val spot: Int) {
    STAIRS("Подъезд № 3, лестничный марш", 0xC9C2B4, 0x7D7468, 0x4A3F33),
    TRASH("Контейнерная площадка", 0x9DB0BF, 0x6B6B66, 0x3E4A2F),
    LEAK("9-й этаж, потолок у лифта", 0xE4E0D6, 0xA89F90, 0x8A6A3A);

    fun draw(rnd: Random): BufferedImage {
        val w = 1280
        val h = 960
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(wall)
        g.fillRect(0, 0, w, h)
        g.color = Color(floor)
        g.fillRect(0, h * 3 / 5, w, h * 2 / 5)
        when (this) {
            STAIRS -> repeat(6) { i ->
                g.color = Color(floor).brighter()
                g.fillRect(i * 120, h * 3 / 5 - i * 70, w, 70)
                g.color = Color(floor).darker()
                g.stroke = BasicStroke(4f)
                g.drawLine(i * 120, h * 3 / 5 - i * 70, w, h * 3 / 5 - i * 70)
            }
            TRASH -> repeat(3) { i ->
                g.color = Color(0x2F5D3A)
                g.fillRoundRect(260 + i * 280, 300, 220, 300, 24, 24)
            }
            LEAK -> repeat(6) { i ->
                g.color = Color(Color(spot).red, Color(spot).green, Color(spot).blue, 55)
                g.fillOval(380 + i * 25, -140 + i * 12, 600 - i * 50, 420 - i * 36)
            }
        }
        repeat(if (this == LEAK) 8 else 45) {
            val s = rnd.nextInt(18, 110)
            g.color = Color(spot).let { c -> Color(c.red, c.green, c.blue, rnd.nextInt(90, 220)) }
            val y = if (this == LEAK) rnd.nextInt(0, 260) else rnd.nextInt(h / 2, h - 140)
            g.fillOval(rnd.nextInt(0, w), y, s, s * 2 / 3)
        }
        g.color = Color(0, 0, 0, 170)
        g.fillRect(0, h - 120, w, 120)
        // без шрифта (нет fontconfig в образе) картинка всё равно нужна — подпись тогда просто пропускаем
        runCatching {
            val font = Seed::class.java.getResourceAsStream("/fonts/Tinos-Bold.ttf")!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }
            g.font = font.deriveFont(44f)
            g.color = Color.WHITE
            g.drawString("ДЕМО-ФОТО · $caption", 40, h - 45)
        }
        g.dispose()
        return img
    }
}
