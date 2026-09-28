package ru.sokolniki.electricity.tariffs

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ru.sokolniki.electricity.domain.Tariffs
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Хранит параметры отдельного сервиса официальных тарифов. */
data class TariffServiceConfig(val token: String, val port: Int, val statePath: Path)

/** Загружает параметры сервиса тарифов из переменных окружения. */
object TariffServiceConfigLoader {
    fun load(environment: Map<String, String> = System.getenv()): TariffServiceConfig {
        val token = environment["TARIFF_SERVICE_TOKEN"]?.trim().orEmpty()
        require(token.isNotEmpty()) { "Не задана переменная окружения TARIFF_SERVICE_TOKEN." }
        val port = environment["PORT"]?.toIntOrNull() ?: 8080
        require(port in 1..65535) { "PORT должен быть числом от 1 до 65535." }
        val statePath = environment["TARIFF_SERVICE_STATE"]?.takeIf { it.isNotBlank() }?.let(Paths::get)
            ?: Paths.get("data", "tariff-service.properties")
        return TariffServiceConfig(token, port, statePath)
    }
}

/** Надёжно сохраняет последнюю успешную официальную пару тарифов в постоянном файле. */
class TariffSnapshotStore(private val path: Path) {
    fun load(): OfficialTariffs? {
        if (!Files.exists(path)) return null
        val properties = Properties().also { Files.newInputStream(path).use(it::load) }
        return OfficialTariffs(
            Tariffs(properties.requiredLong("t1Cents"), properties.requiredLong("t2Cents")),
            properties.requiredValue("sourceUrl"),
            Instant.parse(properties.requiredValue("retrievedAt")),
        )
    }

    fun save(tariffs: OfficialTariffs) {
        val target = path.toAbsolutePath()
        target.parent?.let(Files::createDirectories)
        val properties = Properties().apply {
            setProperty("t1Cents", tariffs.tariffs.t1Cents.toString())
            setProperty("t2Cents", tariffs.tariffs.t2Cents.toString())
            setProperty("sourceUrl", tariffs.sourceUrl)
            setProperty("retrievedAt", tariffs.retrievedAt.toString())
        }
        val temporary = Files.createTempFile(target.parent, "tariffs-", ".properties")
        try {
            Files.newOutputStream(temporary).use { properties.store(it, null) }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun Properties.requiredLong(name: String): Long = requiredValue(name).toLongOrNull()
        ?: error("В сохранённых тарифах поле $name имеет неверный формат.")
    private fun Properties.requiredValue(name: String): String = getProperty(name)
        ?: error("В сохранённых тарифах отсутствует поле $name.")
}

/** Публикует последнюю успешную пару официальных тарифов по защищённому HTTP-адресу. */
class TariffServiceServer(
    private val config: TariffServiceConfig,
    private val fetchTariffs: () -> OfficialTariffs,
    private val store: TariffSnapshotStore,
    private val clock: Clock,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "tariff-service-refresh").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress(config.port), 0).apply {
        executor = Executors.newFixedThreadPool(2) { Thread(it, "tariff-service-http").apply { isDaemon = true } }
        createContext("/v1/tariffs") { handleTariffs(it) }
    }

    fun runForever() {
        server.start()
        scheduler.execute(::refresh)
        scheduler.scheduleAtFixedRate(::refresh, delayUntilNextThreeAm(), DAY_MILLIS, TimeUnit.MILLISECONDS)
        println("Сервис тарифов слушает порт ${config.port}.")
        CountDownLatch(1).await()
    }

    fun stop() {
        scheduler.shutdownNow()
        server.stop(0)
    }

    private fun refresh() = try {
        store.save(fetchTariffs())
        println("Официальные тарифы успешно обновлены.")
    } catch (error: Exception) {
        System.err.println("Не удалось обновить официальные тарифы: ${error.message}")
    }

    private fun handleTariffs(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return exchange.respond(405, "Метод не поддерживается.")
        val expected = "Bearer ${config.token}".toByteArray(StandardCharsets.UTF_8)
        val actual = (exchange.requestHeaders.getFirst("Authorization") ?: "").toByteArray(StandardCharsets.UTF_8)
        if (!MessageDigest.isEqual(expected, actual)) {
            exchange.responseHeaders.set("WWW-Authenticate", "Bearer")
            return exchange.respond(401, "Требуется авторизация.")
        }
        val tariffs = store.load() ?: return exchange.respond(503, "Тарифы ещё не были успешно получены.")
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.respond(200, """{"t1Cents":${tariffs.tariffs.t1Cents},"t2Cents":${tariffs.tariffs.t2Cents},"retrievedAt":"${tariffs.retrievedAt}","sourceUrl":"${tariffs.sourceUrl}"}""")
    }

    private fun HttpExchange.respond(status: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun delayUntilNextThreeAm(): Long {
        val now = ZonedDateTime.now(clock)
        val today = now.toLocalDate().atTime(3, 0).atZone(now.zone)
        return Duration.between(now, if (today.isAfter(now)) today else today.plusDays(1)).toMillis()
    }

    private companion object { val DAY_MILLIS = Duration.ofDays(1).toMillis() }
}

/** Запускает сервис тарифов в отдельном приложении. */
fun runTariffService() {
    val config = TariffServiceConfigLoader.load()
    TariffServiceServer(config, MosenergosbytTariffProvider()::fetch, TariffSnapshotStore(config.statePath), Clock.system(ZoneId.of("Europe/Moscow"))).runForever()
}
