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
import kotlinx.serialization.json.Json

val AppJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

fun main() {
    val cfg = Config.fromEnv()
    Db.init(cfg)

    embeddedServer(Netty, port = cfg.port) {
        install(ContentNegotiation) { json(AppJson) }
        install(CallLogging)
        routing {
            get("/health") { call.respondText("ok") }
        }
    }.start(wait = true)
}
