package ru.sokolniki.electricity.domain

import java.math.BigDecimal

/** Хранит стоимость электроэнергии для Т1 и Т2 в копейках за кВт·ч. */
data class Tariffs(
    val t1Cents: Long,
    val t2Cents: Long,
) {
    init {
        require(t1Cents > 0) { "Тариф Т1 должен быть больше нуля." }
        require(t2Cents > 0) { "Тариф Т2 должен быть больше нуля." }
    }

    val t1Rubles: BigDecimal get() = BigDecimal.valueOf(t1Cents, 2)
    val t2Rubles: BigDecimal get() = BigDecimal.valueOf(t2Cents, 2)
}
