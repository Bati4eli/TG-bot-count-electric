package ru.sokolniki.electricity.tariffs

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Проверяет протокол безопасного получения тарифов ботом из отдельного сервиса. */
class RemoteTariffServiceClientTest {
    @Test
    fun `передаёт секрет и дату кэша`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        try {
            server.createContext("/v1/tariffs") { exchange ->
                assertEquals("Bearer shared-secret", exchange.requestHeaders.getFirst("Authorization"))
                assertEquals("date=2026-09-28", exchange.requestURI.query)
                val response = """{"t1Cents":773,"t2Cents":332,"retrievedAt":"2026-09-28T03:00:00Z","sourceUrl":"https://example.test/calc"}"""
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            server.start()

            val tariffs = RemoteTariffServiceClient(
                URI.create("http://127.0.0.1:${server.address.port}/v1/tariffs"),
                "shared-secret",
            ).fetchFor(LocalDate.parse("2026-09-28"))

            assertEquals(773, tariffs.tariffs.t1Cents)
            assertEquals(332, tariffs.tariffs.t2Cents)
            assertEquals("2026-09-28T03:00:00Z", tariffs.retrievedAt.toString())
        } finally {
            server.stop(0)
        }
    }
}
