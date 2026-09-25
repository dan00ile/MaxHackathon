package mkd

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.security.MessageDigest

val AppJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
private val log = LoggerFactory.getLogger("Application")

fun main() {
    val cfg = Config.fromEnv()
    Db.init(cfg)
    Seed.run()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var bot: Bot? = null
    if (cfg.maxToken.isNotBlank()) {
        val max = MaxBotClient(cfg.maxToken, cfg.maxApiBase)
        val me = runBlocking {
            runCatching { max.me() }.getOrElse { error("неверный MAX_BOT_TOKEN: ${it.message}") }
        }
        botUsername = me["username"]?.jsonPrimitive?.content ?: error("неверный MAX_BOT_TOKEN: в ответе /me нет username")
        log.info("MAX bot me(): {}, botUsername={}", me, botUsername)
        val gigaChat = GigaChatClient(cfg.gigaAuthKey, cfg.gigaScope, cfg.gigaModel)
        val acts = ActService(cfg, max, gigaChat)
        val botInstance = Bot(cfg, max, acts, scope)
        bot = botInstance

        if (cfg.publicUrl.isNotBlank()) {
            val hook = "${cfg.publicUrl}/webhook/max/${cfg.webhookSecret}"
            runBlocking {
                val subs = max.subscriptions()
                subs.filter { it != hook }.forEach { max.unsubscribe(it) }
                if (hook !in subs) max.subscribe(hook)
            }
            log.info("webhook: {}/webhook/max/***", cfg.publicUrl)
        } else {
            val subs = runBlocking { max.subscriptions() }
            if (subs.isEmpty()) {
                scope.launch { botInstance.pollLoop() }
            } else {
                log.info("Webhook держит сервер — события MAX сюда не придут; подавайте их curl'ом на /webhook/max/{secret}")
            }
        }
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
            get("/health") { call.respondText("ok ${System.getenv("GIT_SHA") ?: "dev"}") }
            if (cfg.webhookSecret.isNotBlank()) {
                post("/webhook/max/{secret}") {
                    val secret = call.parameters["secret"] ?: ""
                    if (!MessageDigest.isEqual(secret.toByteArray(), cfg.webhookSecret.toByteArray())) {
                        call.respond(HttpStatusCode.NotFound)
                        return@post
                    }
                    val update = call.receive<Update>()
                    call.respond(HttpStatusCode.OK)
                    bot?.let { b -> scope.launch { runCatching { b.handle(update) }.onFailure { log.error("update", it) } } }
                }
            }
            api(cfg)
        }
    }.start(wait = true)
}
