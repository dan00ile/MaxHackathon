package mkd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object InitData {
    data class Result(val userId: Long, val name: String, val startParam: String?)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun validate(raw: String, botToken: String, now: Instant = Instant.now(), maxAgeSec: Long = 86_400): Result? {
        val params = LinkedHashMap<String, String>()
        for (pair in raw.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx < 0) continue
            val key = pair.substring(0, idx)
            val value = URLDecoder.decode(pair.substring(idx + 1), UTF_8)
            params[key] = value
        }

        val hash = params["hash"] ?: return null

        val dataCheckString = params.entries
            .filter { it.key != "hash" }
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value}" }

        val secret = hmac("WebAppData".toByteArray(UTF_8), botToken.toByteArray(UTF_8))
        val expected = hex(hmac(secret, dataCheckString.toByteArray(UTF_8)))
        if (!MessageDigest.isEqual(expected.toByteArray(UTF_8), hash.lowercase().toByteArray(UTF_8))) return null

        val authDate = params["auth_date"]?.toLongOrNull() ?: return null
        if (now.epochSecond - authDate > maxAgeSec) return null

        val userJson = params["user"] ?: return null
        val user = Json.parseToJsonElement(userJson) as? kotlinx.serialization.json.JsonObject ?: return null

        val userId = (user["id"] ?: user["user_id"])?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        val firstName = user["first_name"]?.jsonPrimitive?.content
        val lastName = user["last_name"]?.jsonPrimitive?.content
        val name = listOfNotNull(firstName, lastName).joinToString(" ").trim().ifBlank { "Житель" }

        return Result(userId, name, params["start_param"])
    }
}
