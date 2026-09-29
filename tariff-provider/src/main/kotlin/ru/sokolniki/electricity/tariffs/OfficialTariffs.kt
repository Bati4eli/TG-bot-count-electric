package ru.sokolniki.electricity.tariffs

import ru.sokolniki.electricity.domain.Tariffs
import java.time.Instant

/** Хранит рекомендацию по тарифам и официальный сервис, из которого она получена. */
data class OfficialTariffs(
    val tariffs: Tariffs,
    val sourceUrl: String,
    val retrievedAt: Instant,
)
