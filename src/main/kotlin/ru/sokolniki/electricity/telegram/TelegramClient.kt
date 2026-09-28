package ru.sokolniki.electricity.telegram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import ru.sokolniki.electricity.persistence.JdbcRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class IncomingMessage(
    val userId: Long,
    val chatId: Long,
    val text: String?,
)

data class IncomingCallback(
    val id: String,
    val userId: Long,
    val chatId: Long?,
    val data: String?,
)

data class IncomingUpdate(
    val updateId: Long,
    val message: IncomingMessage? = null,
    val callback: IncomingCallback? = null,
)

class TelegramClient(token: String) {
    private val apiBase = URI.create("https://api.telegram.org/bot$token/")
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    fun getUpdates(offset: Long): List<IncomingUpdate> {
        val payload = buildJsonObject {
            put("timeout", JsonPrimitive(25))
            put("allowed_updates", buildJsonArray {
                add(JsonPrimitive("message"))
                add(JsonPrimitive("callback_query"))
            })
            if (offset > 0) put("offset", JsonPrimitive(offset))
        }
        val result = call("getUpdates", payload)["result"]?.jsonArray.orEmpty()
        return result.mapNotNull(::parseUpdate)
    }

    fun sendMessage(chatId: Long, text: String, replyMarkup: JsonObject? = null) {
        require(text.length <= 4096) { "Текст Telegram-сообщения превышает 4096 символов." }
        call(
            "sendMessage",
            buildJsonObject {
                put("chat_id", JsonPrimitive(chatId))
                put("text", JsonPrimitive(text))
                replyMarkup?.let { put("reply_markup", it) }
            },
        )
    }

    fun answerCallback(callbackId: String) {
        call("answerCallbackQuery", buildJsonObject { put("callback_query_id", JsonPrimitive(callbackId)) })
    }

    private fun call(method: String, payload: JsonObject): JsonObject {
        val request = HttpRequest.newBuilder(apiBase.resolve(method))
            .timeout(Duration.ofSeconds(40))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Telegram API вернул HTTP ${response.statusCode()}." }
        val body = json.parseToJsonElement(response.body()).jsonObject
        check(body["ok"]?.jsonPrimitive?.content == "true") {
            "Telegram API: ${body["description"]?.jsonPrimitive?.content ?: "неизвестная ошибка"}"
        }
        return body
    }

    private fun parseUpdate(element: kotlinx.serialization.json.JsonElement): IncomingUpdate? {
        val update = element.jsonObject
        val updateId = update["update_id"]?.jsonPrimitive?.longOrNull ?: return null
        val messageObject = update["message"]?.jsonObject
        val callbackObject = update["callback_query"]?.jsonObject
        return IncomingUpdate(
            updateId = updateId,
            message = messageObject?.toIncomingMessage(),
            callback = callbackObject?.toIncomingCallback(),
        )
    }

    private fun JsonObject.toIncomingMessage(): IncomingMessage? {
        val userId = this["from"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull ?: return null
        val chatId = this["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull ?: return null
        return IncomingMessage(userId, chatId, this["text"]?.jsonPrimitive?.content)
    }

    private fun JsonObject.toIncomingCallback(): IncomingCallback? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val userId = this["from"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull ?: return null
        val chatId = this["message"]?.jsonObject?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
        return IncomingCallback(id, userId, chatId, this["data"]?.jsonPrimitive?.content)
    }
}

class BotRunner(
    private val telegram: TelegramClient,
    private val controller: BotController,
    private val repository: JdbcRepository,
) {
    fun runForever() {
        var offset = repository.getLastProcessedUpdateId()?.plus(1) ?: 0L
        while (true) {
            try {
                val updates = telegram.getUpdates(offset).sortedBy { it.updateId }
                for (update in updates) {
                    try {
                        controller.handle(update)
                    } catch (error: Exception) {
                        System.err.println("Не удалось обработать обновление ${update.updateId}: ${error.message}")
                    } finally {
                        repository.saveLastProcessedUpdateId(update.updateId)
                        offset = update.updateId + 1
                    }
                }
            } catch (error: Exception) {
                System.err.println("Ошибка связи с Telegram: ${error.message}")
                Thread.sleep(2_000)
            }
        }
    }
}

