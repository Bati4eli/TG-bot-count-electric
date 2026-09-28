package ru.sokolniki.electricity.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** Рассчитывает расход и ежемесячную оплату по двум последовательным показаниям счётчика. */
class ElectricityCalculator {
    fun calculate(current: MeterReading, previous: MeterReading?): ReadingCalculation {
        val consumption = if (previous == null) {
            Consumption(BigDecimal.ZERO, BigDecimal.ZERO)
        } else {
            val t1 = current.t1Kwh - previous.t1Kwh
            val t2 = current.t2Kwh - previous.t2Kwh
            require(t1 >= BigDecimal.ZERO) { "Новое показание Т1 меньше предыдущего." }
            require(t2 >= BigDecimal.ZERO) { "Новое показание Т2 меньше предыдущего." }
            Consumption(t1, t2)
        }

        val payment = (consumption.t1Kwh * current.tariffs.t1Rubles +
            consumption.t2Kwh * current.tariffs.t2Rubles)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()

        return ReadingCalculation(current, previous, consumption, payment)
    }
}

