package ru.sokolniki.electricity.tariffs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.sokolniki.electricity.domain.Tariffs
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * Loads first-range two-zone tariffs for a rural Moscow Region household with an electric stove.
 *
 * The provider uses the structured endpoint called by the official Mosenergosbyt calculator. It
 * deliberately does not parse the calculator's HTML output.
 */
class MosenergosbytTariffProvider {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val http = HttpClient.newBuilder()
        .cookieHandler(cookies)
        .connectTimeout(REQUEST_TIMEOUT)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Retrieves the current T1 and T2 tariffs from the official calculator endpoint. */
    fun fetch(): OfficialTariffs {
        val page = sendGet(CALCULATOR_URI)
        val csrfToken = CSRF_PATTERN.find(page)?.groupValues?.get(1)
            ?: error("Мосэнергосбыт не передал CSRF-токен для проверки тарифов.")
        val body = formBody(
            "consumptionArray[t1]" to "1",
            "consumptionArray[t2]" to "1",
            "location" to "MSC_DISTRICT",
            "locationType" to "COUNTRY",
            "electricStoveUse" to "Y",
            "sessid" to csrfToken,
        )
        val response = sendPost(CALCULATOR_ACTION_URI, body)
        return parseResponse(response)
    }

    private fun parseResponse(body: String): OfficialTariffs {
        val root = json.parseToJsonElement(body).jsonObject
        check(root["status"]?.jsonPrimitive?.content == "success") {
            "Мосэнергосбыт не вернул тарифы: ${root["errors"] ?: "неизвестная ошибка"}."
        }
        val rows = root["data"]?.jsonObject
            ?.get("selected")?.jsonObject
            ?.get("report")?.jsonArray
            ?: error("Мосэнергосбыт вернул ответ без тарифной таблицы.")
        val prices = rows.associate { row ->
            val fields = row.jsonObject
            fields["zoneNumber"]!!.jsonPrimitive.content.toInt() to fields["price"]!!.jsonPrimitive.content
        }
        return OfficialTariffs(
            tariffs = Tariffs(
                t1Cents = decimalToCents(requireNotNull(prices[1]) { "В ответе нет тарифа Т1." }),
                t2Cents = decimalToCents(requireNotNull(prices[2]) { "В ответе нет тарифа Т2." }),
            ),
            sourceUrl = CALCULATOR_URI.toString(),
        )
    }

    private fun sendGet(uri: URI): String = http.send(
        HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    ).let(::successfulBody)

    private fun sendPost(uri: URI, body: String): String = http.send(
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    ).let(::successfulBody)

    private fun successfulBody(response: HttpResponse<String>): String {
        check(response.statusCode() in 200..299) {
            "Мосэнергосбыт вернул HTTP ${response.statusCode()} при запросе тарифов."
        }
        return response.body()
    }

    private fun formBody(vararg fields: Pair<String, String>): String = fields.joinToString("&") { (name, value) ->
        "${urlEncode(name)}=${urlEncode(value)}"
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun decimalToCents(value: String): Long = BigDecimal(value)
        .movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()

    private companion object {
        val CALCULATOR_URI = URI.create("https://www.mosenergosbyt.ru/individuals/tariffs-n-payments/calc/")
        val CALCULATOR_ACTION_URI = URI.create(
            "https://www.mosenergosbyt.ru/bitrix/services/main/ajax.php?action=" +
                "sigma%3Atariffs.Calculator.calculateByParameters&mode=class",
        )
        val REQUEST_TIMEOUT = Duration.ofSeconds(30)
        val CSRF_PATTERN = Regex("'bitrix_sessid':'([^']+)'")
    }
}

/** Holds a tariff recommendation together with the official service from which it was retrieved. */
data class OfficialTariffs(
    val tariffs: Tariffs,
    val sourceUrl: String,
    val retrievedAt: Instant = Instant.now(),
)
