package ru.sokolniki.electricity.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ElectricityCalculatorTest {
    private val calculator = ElectricityCalculator()
    private val tariffs = Tariffs(t1Cents = 733, t2Cents = 332)

    @Test
    fun `calculates and rounds payment like source workbook`() {
        val previous = reading(id = 1, t1 = 744_000, t2 = 295_100)
        val current = reading(id = 2, t1 = 785_056, t2 = 355_253)

        val result = calculator.calculate(current, previous)

        assertEquals("410.56", result.consumption.t1Kwh.toPlainString())
        assertEquals("601.53", result.consumption.t2Kwh.toPlainString())
        assertEquals(5_006, result.paymentRubles)
    }

    @Test
    fun `first reading has zero consumption`() {
        val result = calculator.calculate(reading(id = 1, t1 = 1_000, t2 = 2_000), null)

        assertEquals("0", result.consumption.totalKwh.toPlainString())
        assertEquals(0, result.paymentRubles)
    }

    @Test
    fun `rejects meter rollback`() {
        val previous = reading(id = 1, t1 = 20_000, t2 = 10_000)
        val current = reading(id = 2, t1 = 19_999, t2 = 10_000)

        assertFailsWith<IllegalArgumentException> { calculator.calculate(current, previous) }
    }

    private fun reading(id: Long, t1: Long, t2: Long): MeterReading = MeterReading(
        id = id,
        telegramUserId = 42,
        date = LocalDate.of(2026, 4, 26),
        t1Hundredths = t1,
        t2Hundredths = t2,
        tariffs = tariffs,
    )
}

