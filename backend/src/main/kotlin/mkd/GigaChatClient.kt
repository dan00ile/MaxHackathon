package mkd

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable private data class OAuthResponse(@SerialName("access_token") val accessToken: String, @SerialName("expires_at") val expiresAt: Long)
@Serializable private data class ChatMessageDto(val role: String, val content: String, val attachments: List<String>? = null)
@Serializable private data class ChatRequest(val model: String, val temperature: Double, val messages: List<ChatMessageDto>)
@Serializable private data class ChatChoice(val message: ChatMessageDto)
@Serializable private data class ChatResponse(val choices: List<ChatChoice>)
@Serializable private data class FileUploadResponse(val id: String)

class GigaChatClient(private val authKey: String, private val scope: String, private val model: String) {
    private val oauthBase = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
    private val apiBase = "https://gigachat.devices.sberbank.ru/api/v1"

    private val http = HttpClient(Java) {
        install(ContentNegotiation) { json(AppJson) }
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
    }

    private val tokenMutex = Mutex()
    private var cachedToken: String? = null
    private var tokenExpiresAt: Instant = Instant.EPOCH

    val enabled: Boolean get() = authKey.isNotBlank()

    private suspend fun accessToken(): String = tokenMutex.withLock {
        cachedToken?.let { if (Instant.now().isBefore(tokenExpiresAt)) return@withLock it }
        val response: OAuthResponse = http.post(oauthBase) {
            header(HttpHeaders.Authorization, "Basic $authKey")
            header("RqUID", UUID.randomUUID().toString())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("scope=$scope")
        }.body()
        cachedToken = response.accessToken
        tokenExpiresAt = Instant.ofEpochMilli(response.expiresAt).minusSeconds(60)
        response.accessToken
    }

    suspend fun chat(system: String, user: String, attachments: List<String> = emptyList()): String {
        check(enabled) { "GigaChat не настроен" }
        val token = accessToken()
        val request = ChatRequest(
            model = model,
            temperature = 0.1,
            messages = listOf(
                ChatMessageDto("system", system),
                ChatMessageDto("user", user, attachments.ifEmpty { null }),
            ),
        )
        val response: ChatResponse = http.post("$apiBase/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
        return response.choices.first().message.content
    }

    suspend fun uploadFile(bytes: ByteArray, fileName: String, mime: String): String {
        check(enabled) { "GigaChat не настроен" }
        val token = accessToken()
        val response: FileUploadResponse = http.post("$apiBase/files") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", "general")
                        append(
                            "file", bytes,
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, ContentDisposition.File.withParameter(ContentDisposition.Parameters.FileName, fileName).toString())
                                append(HttpHeaders.ContentType, mime)
                            },
                        )
                    },
                ),
            )
        }.body()
        return response.id
    }
}
