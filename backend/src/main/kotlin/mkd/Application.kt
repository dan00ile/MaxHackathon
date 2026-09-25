package mkd

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

val AppJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
private val log = LoggerFactory.getLogger("Application")

fun main() {
    val cfg = Config.fromEnv()
    Db.init(cfg)
    Seed.run()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    if (cfg.maxToken.isNotBlank()) {
        val max = MaxBotClient(cfg.maxToken, cfg.maxApiBase)
        val me = runBlocking {
            runCatching { max.me() }.getOrElse { error("неверный MAX_BOT_TOKEN: ${it.message}") }
        }
        botUsername = me["username"]?.jsonPrimitive?.content ?: error("неверный MAX_BOT_TOKEN: в ответе /me нет username")
        log.info("MAX bot me(): {}, botUsername={}", me, botUsername)
        val bot = Bot(max)
        scope.launch { bot.pollLoop() }
    } else {
        log.warn("MAX_BOT_TOKEN не задан, бот выключен")
    }

    embeddedServer(Netty, port = cfg.port) {
        install(ContentNegotiation) { json(AppJson) }
        install(CallLogging)
        install(StatusPages) {
            exception<ApiError> { call, e -> call.respond(e.status, ErrorDto(e.code, e.message)) }
        }
        install(CORS) {
            val originHost = cfg.corsOrigin.substringAfter("://")
            allowHost(originHost, schemes = listOf("https", "http"))
            allowHeader("X-Max-Init-Data")
            allowHeader("X-Dev-User-Id")
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Put)
        }
        routing {
            get("/health") { call.respondText("ok") }
            api(cfg)
        }
    }.start(wait = true)
}
