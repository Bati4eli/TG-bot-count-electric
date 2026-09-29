package ru.sokolniki.electricity.tariffs

import ru.sokolniki.electricity.domain.Tariffs
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/** Проверяет, что отдельный сервис кэширует официальные тарифы по календарной дате. */
class TariffServiceServerTest {
    @Test
    fun `повторный запрос в ту же дату читает кэш а новая дата обновляет его`() {
        val calls = AtomicInteger()
        val service = TariffServiceServer(
            TariffServiceConfig(token = "shared-secret", port = 0),
            fetchTariffs = {
                val number = calls.incrementAndGet().toLong()
                OfficialTariffs(Tariffs(700 + number, 300 + number), "https://example.test", Instant.parse("2026-01-01T00:00:00Z"))
            },
        )
        service.start()
        try {
            val port = service.port
            assertEquals(200, request(port, "date=2026-09-28").statusCode())
            assertEquals(200, request(port, "date=2026-09-28").statusCode())
            assertEquals(1, calls.get())

            assertEquals(200, request(port, "date=2026-09-29").statusCode())
            assertEquals(2, calls.get())
        } finally {
            service.stop()
        }
    }

    private fun request(port: Int, query: String? = null): HttpResponse<String> {
        val suffix = query?.let { "?$it" }.orEmpty()
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/v1/tariffs$suffix"))
                .header("Authorization", "Bearer shared-secret")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }
}
