package ru.sokolniki.electricity.tariffs

import com.sun.net.httpserver.HttpServer
import ru.sokolniki.electricity.domain.Tariffs
import ru.sokolniki.electricity.domain.UserProfile
import ru.sokolniki.electricity.persistence.JdbcRepository
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/** Проверяет отбор получателей фоновой рассылки официальных тарифов. */
class TariffUpdateServiceTest {
    @Test
    fun `does not notify users when official tariffs have not changed`() {
        val database = Files.createTempFile("electricity-tariff-update-test", ".db")
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            server.createContext("/v1/tariffs") { exchange ->
                val response = """{"t1Cents":773,"t2Cents":332,"retrievedAt":"2026-09-30T03:00:00Z","sourceUrl":"https://example.test/calc"}"""
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            server.start()

            val clock = Clock.fixed(Instant.parse("2026-09-30T09:00:00Z"), ZoneOffset.UTC)
            val repository = JdbcRepository(database, clock)
            repository.migrate()
            repository.saveProfile(UserProfile(1, 101, "1"))
            repository.saveActiveTariffs(1, Tariffs(773, 332))
            repository.saveOfficialTariffs(Tariffs(773, 332))
            val updates = TariffUpdateService(
                repository,
                RemoteTariffServiceClient(URI.create("http://127.0.0.1:${server.address.port}/v1/tariffs"), "test-token"),
                clock,
            )

            assertEquals(emptyList(), updates.refresh())
        } finally {
            server.stop(0)
            Files.deleteIfExists(database)
        }
    }
}
