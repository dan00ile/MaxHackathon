package mkd

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

class Bot(private val max: MaxBotClient) {
    private val log = LoggerFactory.getLogger(Bot::class.java)

    suspend fun pollLoop() {
        var marker: Long? = null
        while (true) {
            try {
                val list = max.getUpdates(marker)
                list.updates.forEach { update ->
                    runCatching { handle(update) }.onFailure { log.error("update", it) }
                }
                marker = list.marker ?: marker
            } catch (e: Exception) {
                log.warn("poll failed: {}", e.message)
                delay(3000)
            }
        }
    }

    suspend fun handle(u: Update) {
        val text = u.message?.body?.text ?: return
        val userId = u.message.sender?.userId ?: return
        max.sendText(userId, "Эхо: $text")
    }
}
