package mkd

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MaxUser(
    @SerialName("user_id") val userId: Long,
    val name: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
) {
    fun displayName() = name ?: listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { "Житель" }
}

@Serializable
data class Recipient(@SerialName("chat_id") val chatId: Long? = null, @SerialName("user_id") val userId: Long? = null)
@Serializable
data class MessageBody(val mid: String, val text: String? = null, val attachments: List<JsonObject> = emptyList())
@Serializable
data class Message(val sender: MaxUser? = null, val recipient: Recipient, val timestamp: Long, val body: MessageBody)
@Serializable
data class Callback(@SerialName("callback_id") val callbackId: String, val payload: String? = null, val user: MaxUser)
@Serializable
data class Update(
    @SerialName("update_type") val type: String,
    val timestamp: Long,                        // unix ms
    val message: Message? = null,               // message_created, message_callback
    val callback: Callback? = null,             // message_callback
    val user: MaxUser? = null,                  // bot_started
    @SerialName("chat_id") val chatId: Long? = null,
    val payload: String? = null,                // bot_started: payload диплинка
)

@Serializable
data class UpdateList(val updates: List<Update>, val marker: Long? = null)
@Serializable
data class Button(val type: String, val text: String, val payload: String? = null, val url: String? = null)

fun cb(text: String, payload: String) = Button("callback", text, payload = payload)
fun link(text: String, url: String) = Button("link", text, url = url)

@Serializable
data class UploadUrl(val url: String)
@Serializable
data class UploadToken(val token: String)
@Serializable
data class InlineKeyboardPayload(val buttons: List<List<Button>>)
@Serializable
data class Attachment(val type: String, val payload: kotlinx.serialization.json.JsonElement)
@Serializable
data class SendMessageRequest(val text: String? = null, val attachments: List<Attachment> = emptyList())
// message != null — MAX заменяет сообщение, на котором нажата кнопка, вместо отправки нового
@Serializable
data class AnswerRequest(val notification: String? = null, val message: SendMessageRequest? = null)

@Serializable
data class SubscriptionDto(val url: String)
@Serializable
data class SubscriptionsResponse(val subscriptions: List<SubscriptionDto> = emptyList())
@Serializable
data class SubscribeRequest(val url: String, @SerialName("update_types") val updateTypes: List<String>)

private val webhookUpdateTypes = listOf("message_created", "message_callback", "bot_started")

lateinit var botUsername: String

class MaxBotClient(private val token: String, private val base: String) {
    private val log = org.slf4j.LoggerFactory.getLogger(MaxBotClient::class.java)

    private val http = HttpClient(Java) {
        install(ContentNegotiation) { json(AppJson) }
        install(HttpTimeout) { requestTimeoutMillis = 45_000 }
    }

    private fun keyboardAttachment(buttons: List<List<Button>>): Attachment? {
        if (buttons.isEmpty()) return null
        val payload = AppJson.encodeToJsonElement(InlineKeyboardPayload.serializer(), InlineKeyboardPayload(buttons))
        return Attachment("inline_keyboard", payload)
    }

    private suspend fun HttpResponse.checkOk(): HttpResponse {
        if (status == HttpStatusCode.TooManyRequests) {
            delay(1000)
        }
        return this
    }

    suspend fun me(): JsonObject =
        http.get("$base/me") { header(HttpHeaders.Authorization, token) }.body()

    suspend fun getUpdates(marker: Long?): UpdateList {
        val response = http.get("$base/updates") {
            header(HttpHeaders.Authorization, token)
            url {
                parameters.append("timeout", "30")
                parameters.append("types", "message_created,message_callback,bot_started")
                if (marker != null) parameters.append("marker", marker.toString())
            }
        }
        return response.body()
    }

    fun messageBody(text: String, buttons: List<List<Button>> = emptyList()) =
        SendMessageRequest(text = text, attachments = listOfNotNull(keyboardAttachment(buttons)))

    suspend fun sendText(userId: Long, text: String, buttons: List<List<Button>> = emptyList()) {
        sendMessage(userId, messageBody(text, buttons))
    }

    suspend fun sendMessage(userId: Long, body: SendMessageRequest) {
        send(userId, body)
    }

    private suspend fun send(userId: Long, body: SendMessageRequest, retriesLeft: Int = 5) {
        val response = http.post("$base/messages") {
            header(HttpHeaders.Authorization, token)
            url { parameters.append("user_id", userId.toString()) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            delay(1000)
            send(userId, body, retriesLeft)
            return
        }
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            if (text.contains("attachment.not.ready") && retriesLeft > 0) {
                delay(1000)
                send(userId, body, retriesLeft - 1)
                return
            }
            log.warn("sendText failed: {} {}", response.status, text)
        }
    }

    suspend fun sendFile(
        userId: Long,
        bytes: ByteArray,
        fileName: String,
        text: String,
        buttons: List<List<Button>> = emptyList()
    ) {
        val uploadUrlResp = http.post("$base/uploads") {
            header(HttpHeaders.Authorization, token)
            url { parameters.append("type", "file") }
        }
        val uploadUrl: UploadUrl = uploadUrlResp.body()

        val mime = if (fileName.endsWith(".pdf", ignoreCase = true)) "application/pdf" else "application/octet-stream"
        val uploadResp = http.post(uploadUrl.url) {
            setBody(MultiPartFormDataContent(formData {
                append("data", bytes, Headers.build {
                    append(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.File.withParameter(ContentDisposition.Parameters.FileName, fileName)
                            .toString()
                    )
                    append(HttpHeaders.ContentType, mime)
                })
            }))
        }
        val uploadToken: UploadToken = uploadResp.body()

        val attachments = mutableListOf(
            Attachment("file", AppJson.encodeToJsonElement(UploadToken.serializer(), uploadToken))
        )
        keyboardAttachment(buttons)?.let { attachments.add(it) }
        send(userId, SendMessageRequest(text = text, attachments = attachments))
    }

    // false — MAX не принял ответ (например, сообщение слишком старое для замены): решает вызывающий
    suspend fun answerCallback(
        callbackId: String,
        notification: String? = null,
        message: SendMessageRequest? = null,
    ): Boolean {
        val response = http.post("$base/answers") {
            header(HttpHeaders.Authorization, token)
            url { parameters.append("callback_id", callbackId) }
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest(notification, message))
        }
        if (!response.status.isSuccess()) {
            log.warn("answerCallback failed: {} {}", response.status, response.bodyAsText())
            return false
        }
        return true
    }

    suspend fun download(url: String): ByteArray = http.get(url).body()

    suspend fun subscriptions(): List<String> {
        val response: SubscriptionsResponse = http.get("$base/subscriptions") {
            header(HttpHeaders.Authorization, token)
        }.body()
        return response.subscriptions.map { it.url }
    }

    suspend fun subscribe(url: String) {
        http.post("$base/subscriptions") {
            header(HttpHeaders.Authorization, token)
            contentType(ContentType.Application.Json)
            setBody(SubscribeRequest(url, webhookUpdateTypes))
        }
    }

    suspend fun unsubscribe(url: String) {
        http.delete("$base/subscriptions") {
            header(HttpHeaders.Authorization, token)
            url { parameters.append("url", url) }
        }
    }
}
