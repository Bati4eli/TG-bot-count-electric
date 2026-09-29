package ru.sokolniki.electricity.tariffs

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Хранит параметры отдельного сервиса официальных тарифов. */
data class TariffServiceConfig(val token: String, val port: Int)

/** Загружает параметры сервиса тарифов из переменных окружения. */
object TariffServiceConfigLoader {
    fun load(environment: Map<String, String> = System.getenv()): TariffServiceConfig {
        val token = environment["TARIFF_SERVICE_TOKEN"]?.trim().orEmpty()
        require(token.isNotEmpty()) { "Не задана переменная окружения TARIFF_SERVICE_TOKEN." }
        val port = environment["PORT"]?.toIntOrNull() ?: 8080
        require(port in 1..65535) { "PORT должен быть числом от 1 до 65535." }
        return TariffServiceConfig(token, port)
    }
}

/**
 * По запросу основного приложения получает официальные тарифы и хранит их только в памяти.
 *
 * Здесь нет собственного расписания: момент обновления определяет Telegram-бот.
 */
class TariffServiceServer(
    private val config: TariffServiceConfig,
    private val fetchTariffs: () -> OfficialTariffs,
) {
    private val cacheLock = Any()
    private var cachedTariffs: OfficialTariffs? = null
    private val server = HttpServer.create(InetSocketAddress(config.port), 0).apply {
        executor = Executors.newFixedThreadPool(2) { Thread(it, "tariff-service-http").apply { isDaemon = true } }
        createContext("/v1/tariffs") { handleTariffs(it) }
    }

    /** Фактический порт HTTP-сервера, в том числе выбранный автоматически для тестов. */
    val port: Int get() = server.address.port

    fun runForever() {
        start()
        CountDownLatch(1).await()
    }

    /** Запускает HTTP-сервер без фонового обращения к официальному калькулятору. */
    fun start() {
        server.start()
        println("Сервис тарифов слушает порт ${config.port}.")
    }

    fun stop() {
        server.stop(0)
    }

    private fun loadTariffs(forceRefresh: Boolean): OfficialTariffs = synchronized(cacheLock) {
        if (!forceRefresh) cachedTariffs?.let { return@synchronized it }
        refreshCache()
    }

    private fun refreshCache(): OfficialTariffs {
        repeat(REFRESH_ATTEMPTS) { attempt ->
            try {
                val tariffs = fetchTariffs()
                cachedTariffs = tariffs
                println("Официальные тарифы успешно обновлены.")
                return tariffs
            } catch (error: Exception) {
                val details = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name
                System.err.println(
                    "Не удалось обновить официальные тарифы, попытка ${attempt + 1}/$REFRESH_ATTEMPTS: $details",
                )
                error.printStackTrace(System.err)
            }
        }
        return requireNotNull(cachedTariffs) {
            "Не удалось получить официальные тарифы и в памяти нет предыдущего значения."
        }
    }

    private fun handleTariffs(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return exchange.respond(405, "Метод не поддерживается.")
        val expected = "Bearer ${config.token}".toByteArray(StandardCharsets.UTF_8)
        val actual = (exchange.requestHeaders.getFirst("Authorization") ?: "").toByteArray(StandardCharsets.UTF_8)
        if (!MessageDigest.isEqual(expected, actual)) {
            exchange.responseHeaders.set("WWW-Authenticate", "Bearer")
            return exchange.respond(401, "Требуется авторизация.")
        }
        val forceRefresh = exchange.requestURI.query?.split('&')?.any { it == "refresh=true" } == true
        val tariffs = try {
            loadTariffs(forceRefresh)
        } catch (error: Exception) {
            val details = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name
            return exchange.respond(503, "Не удалось получить официальные тарифы: $details")
        }
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.respond(200, """{"t1Cents":${tariffs.tariffs.t1Cents},"t2Cents":${tariffs.tariffs.t2Cents},"retrievedAt":"${tariffs.retrievedAt}","sourceUrl":"${tariffs.sourceUrl}"}""")
    }

    private fun HttpExchange.respond(status: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val REFRESH_ATTEMPTS = 3
    }
}

/** Запускает сервис тарифов в отдельном приложении. */
fun runTariffService() {
    val config = TariffServiceConfigLoader.load()
    TariffServiceServer(config, MosenergosbytTariffProvider()::fetch).runForever()
}
