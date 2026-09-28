package ru.sokolniki.electricity.tariffs

import com.sun.org.slf4j.internal.LoggerFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.sokolniki.electricity.domain.Tariffs
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** Получает сохранённые тарифы из отдельного защищённого сервиса тарифов. */
class RemoteTariffServiceClient(
    private val serviceUri: URI,
    private val token: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(token.isNotBlank()) { "Не задан секрет для сервиса тарифов." }
    }

    /** Загружает последнюю успешную пару тарифов, не обращаясь к Мосэнергосбыту напрямую. */
    fun fetch(): OfficialTariffs {
        try {
            val response = http.send(
                HttpRequest.newBuilder(serviceUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            check(response.statusCode() == 200) {
                "Сервис тарифов вернул HTTP ${response.statusCode()}."
            }
            val body = json.parseToJsonElement(response.body()).jsonObject
            return OfficialTariffs(
                tariffs = Tariffs(
                    t1Cents = body.requiredLong("t1Cents"),
                    t2Cents = body.requiredLong("t2Cents"),
                ),
                sourceUrl = body.requiredString("sourceUrl"),
                retrievedAt = Instant.parse(body.requiredString("retrievedAt")),
            )
        } catch (e: Exception) {
            System.err.println("Ошибочка вышла при получении тарифов: " + e.message)
            throw e
        }

    }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.requiredLong(name: String): Long =
        get(name)?.jsonPrimitive?.content?.toLongOrNull() ?: error("Сервис тарифов не передал поле $name.")

    private fun Map<String, kotlinx.serialization.json.JsonElement>.requiredString(name: String): String =
        get(name)?.jsonPrimitive?.content ?: error("Сервис тарифов не передал поле $name.")

    private companion object {
        val REQUEST_TIMEOUT = Duration.ofSeconds(15)
    }
}
