package mkd

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

val AppJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
private val log = LoggerFactory.getLogger("Application")

fun main() {
    val cfg = Config.fromEnv()
    Db.init(cfg)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    if (cfg.maxToken.isNotBlank()) {
        val max = MaxBotClient(cfg.maxToken, cfg.maxApiBase)
        val bot = Bot(max)
        scope.launch { bot.pollLoop() }
    } else {
        log.warn("MAX_BOT_TOKEN не задан, бот выключен")
    }

    embeddedServer(Netty, port = cfg.port) {
        install(ContentNegotiation) { json(AppJson) }
        install(CallLogging)
        routing {
            get("/health") { call.respondText("ok") }
        }
    }.start(wait = true)
}
