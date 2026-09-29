package mkd

import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.ZoneId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// На чистой базе проверяющий должен сразу увидеть по дому акт в каждом статусе — с замечаниями и фото
class SeedTest {
    private val cfg = Config(
        port = 0, dbUrl = "", dbUser = "", dbPassword = "",
        maxToken = "", maxApiBase = "http://127.0.0.1:1",
        gigaAuthKey = "", gigaScope = "", gigaModel = "",
        corsOrigin = "", adminUserIds = emptySet(), demoMode = true, devAuth = false,
        filesDir = "build/tmp/test-files-seed", zone = ZoneId.of("Europe/Moscow"), publicUrl = "", webhookSecret = "",
    )

    @BeforeTest
    fun setUp() {
        transaction(testDb) { allTables.reversed().forEach { it.deleteAll() } }
    }

    @Test
    fun `seed creates one house with an act in every status`() {
        Seed.run(cfg)
        Seed.run(cfg) // повторный старт не дублирует данные

        transaction(testDb) {
            assertEquals(1, Houses.selectAll().count())
            val acts = Acts.selectAll().toList()
            assertEquals(ActStatus.entries.toSet(), acts.map { it[Acts.status] }.toSet())
            assertTrue(acts.all { it[Acts.fileHash] == null }, "иначе загрузка demo-act.pdf упрётся в дубликат")
            assertTrue(acts.all { File(it[Acts.filePath]).exists() })

            val photos = Attachments.selectAll().map { File(it[Attachments.filePath]) }
            assertTrue(photos.isNotEmpty() && photos.all { it.length() > 0 })

            val refusal = Refusals.selectAll().single()
            val pdf = File(refusal[Refusals.pdfPath]!!)
            assertEquals("%PDF", pdf.readBytes().take(4).toByteArray().decodeToString())
            assertTrue(Attachments.selectAll().any { it[Attachments.registryNo] != null })
        }
    }
}
