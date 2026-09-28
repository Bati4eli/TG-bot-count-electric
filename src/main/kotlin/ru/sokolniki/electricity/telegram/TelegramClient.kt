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
import java.util.UUID

/** Представляет необходимые боту поля сообщения из обновления Telegram. */
data class IncomingMessage(
    val userId: Long,
    val chatId: Long,
    val text: String?,
    val document: IncomingDocument? = null,
)

/** Описывает документ, приложенный к входящему сообщению Telegram. */
data class IncomingDocument(
    val fileId: String,
    val fileName: String?,
    val fileSize: Long?,
)

/** Представляет обратный вызов от встроенной клавиатуры, полученный из Telegram. */
data class IncomingCallback(
    val id: String,
    val userId: Long,
    val chatId: Long?,
    val data: String?,
)

/** Представляет поддерживаемые части обновления Telegram. */
data class IncomingUpdate(
    val updateId: Long,
    val message: IncomingMessage? = null,
    val callback: IncomingCallback? = null,
)

/** Минимальный HTTP-клиент для Telegram Bot API и загрузки файлов из Telegram. */
class TelegramClient(private val token: String) {
    private val apiBase = URI.create("https://api.telegram.org/bot$token/")
    private val fileApiBase = "https://api.telegram.org/file/bot$token/"
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

    fun sendMessage(chatId: Long, text: String, replyMarkup: JsonObject? = null, parseMode: String? = null) {
        require(text.length <= 4096) { "Текст Telegram-сообщения превышает 4096 символов." }
        call(
            "sendMessage",
            buildJsonObject {
                put("chat_id", JsonPrimitive(chatId))
                put("text", JsonPrimitive(text))
                replyMarkup?.let { put("reply_markup", it) }
                parseMode?.let { put("parse_mode", JsonPrimitive(it)) }
            },
        )
    }

    fun sendDocument(
        chatId: Long,
        fileName: String,
        bytes: ByteArray,
        caption: String,
        replyMarkup: JsonObject? = null,
    ) {
        require(bytes.isNotEmpty()) { "Нельзя отправить пустой файл." }
        require(bytes.size <= MAX_DOCUMENT_SIZE_BYTES) { "Файл истории слишком большой." }
        val boundary = "----ElectricityBot${UUID.randomUUID()}"
        val body = buildMultipartBody(
            boundary = boundary,
            fields = buildMap {
                put("chat_id", chatId.toString())
                put("caption", caption)
                replyMarkup?.let { put("reply_markup", it.toString()) }
            },
            fileName = fileName,
            bytes = bytes,
        )
        val request = HttpRequest.newBuilder(apiBase.resolve("sendDocument"))
            .timeout(Duration.ofSeconds(40))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        parseResponse(httpClient.send(request, HttpResponse.BodyHandlers.ofString()))
    }

    fun downloadDocument(document: IncomingDocument): ByteArray {
        document.fileSize?.let { size ->
            require(size <= MAX_DOCUMENT_SIZE_BYTES) { "Файл слишком большой. Максимум: 5 МБ." }
        }
        val result = call("getFile", buildJsonObject { put("file_id", JsonPrimitive(document.fileId)) })
        val filePath = result["result"]?.jsonObject?.get("file_path")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Telegram не вернул путь к файлу.")
        val request = HttpRequest.newBuilder(URI.create(fileApiBase + filePath))
            .timeout(Duration.ofSeconds(40))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() in 200..299) { "Не удалось скачать файл из Telegram." }
        require(response.body().size <= MAX_DOCUMENT_SIZE_BYTES) { "Файл слишком большой. Максимум: 5 МБ." }
        return response.body()
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
        return parseResponse(httpClient.send(request, HttpResponse.BodyHandlers.ofString()))
    }

    private fun parseResponse(response: HttpResponse<String>): JsonObject {
        check(response.statusCode() in 200..299) { "Telegram API вернул HTTP ${response.statusCode()}." }
        val body = json.parseToJsonElement(response.body()).jsonObject
        check(body["ok"]?.jsonPrimitive?.content == "true") {
            "Telegram API: ${body["description"]?.jsonPrimitive?.content ?: "неизвестная ошибка"}"
        }
        return body
    }

    private fun buildMultipartBody(
        boundary: String,
        fields: Map<String, String>,
        fileName: String,
        bytes: ByteArray,
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        fun writeText(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
        fields.forEach { (name, value) ->
            writeText("--$boundary\r\n")
            writeText("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            writeText(value)
            writeText("\r\n")
        }
        writeText("--$boundary\r\n")
        writeText("Content-Disposition: form-data; name=\"document\"; filename=\"$fileName\"\r\n")
        writeText("Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n")
        output.write(bytes)
        writeText("\r\n--$boundary--\r\n")
        return output.toByteArray()
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
        val documentObject = this["document"]?.jsonObject
        val document = documentObject?.let {
            val fileId = it["file_id"]?.jsonPrimitive?.content ?: return@let null
            IncomingDocument(
                fileId = fileId,
                fileName = it["file_name"]?.jsonPrimitive?.content,
                fileSize = it["file_size"]?.jsonPrimitive?.longOrNull,
            )
        }
        return IncomingMessage(userId, chatId, this["text"]?.jsonPrimitive?.content, document)
    }

    private fun JsonObject.toIncomingCallback(): IncomingCallback? {
        val id = this["id"]?.jsonPrimitive?.content ?: return null
        val userId = this["from"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull ?: return null
        val chatId = this["message"]?.jsonObject?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
        return IncomingCallback(id, userId, chatId, this["data"]?.jsonPrimitive?.content)
    }
}

/** Выполняет длинный опрос, распределяет обновления и сохраняет идентификатор последнего обработанного. */
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

private const val MAX_DOCUMENT_SIZE_BYTES = 5 * 1024 * 1024

