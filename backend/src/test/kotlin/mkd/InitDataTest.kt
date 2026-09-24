package mkd

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InitDataTest {
    private val token = "test-token"

    private fun build(params: Map<String, String>): String {
        val dataCheckString = params.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
        val secret = InitData.hmac("WebAppData".toByteArray(UTF_8), token.toByteArray(UTF_8))
        val hash = InitData.hmac(secret, dataCheckString.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
        val all = params + ("hash" to hash)
        return all.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, UTF_8)}" }
    }

    private fun baseParams(authDate: Long = Instant.now().epochSecond) = mapOf(
        "auth_date" to authDate.toString(),
        "user" to """{"id":42,"first_name":"Иван","last_name":"Петров"}""",
        "start_param" to "act1",
    )

    @Test fun `valid initData returns userId`() {
        val raw = build(baseParams())
        val result = InitData.validate(raw, token)
        assertEquals(42L, result?.userId)
        assertEquals("Иван Петров", result?.name)
        assertEquals("act1", result?.startParam)
    }

    @Test fun `tampered user field fails`() {
        val raw = build(baseParams()).replace("%3A42", "%3A43")
        assertNull(InitData.validate(raw, token))
    }

    @Test fun `old auth_date fails`() {
        val raw = build(baseParams(authDate = Instant.now().epochSecond - 2 * 86_400))
        assertNull(InitData.validate(raw, token))
    }

    @Test fun `missing hash fails`() {
        val raw = "auth_date=${Instant.now().epochSecond}&user=%7B%22id%22%3A42%7D"
        assertNull(InitData.validate(raw, token))
    }
}
