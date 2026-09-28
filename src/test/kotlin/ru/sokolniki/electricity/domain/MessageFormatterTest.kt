package ru.sokolniki.electricity.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageFormatterTest {
    private val calculator = ElectricityCalculator()
    private val formatter = MessageFormatter()
    private val profile = UserProfile(telegramUserId = 42, chatId = 100, plotNumber = "99")
    private val tariffs = Tariffs(t1Cents = 733, t2Cents = 332)

    @Test
    fun `formats compact bank message from O4 pattern`() {
        assertEquals(
            "Уч.99: эл-во. Расход T1:411 T2:602. Показания T1:7851 T2:3553",
            formatter.bankMessage(profile, calculation()),
        )
    }

    @Test
    fun `formats full chairman message from N4 pattern`() {
        assertEquals(
            """
            уч. 99
            Показания счетчиков на 26.04.2026
            ТО: 11403
            Т1: 7851
            Т2: 3553

            Расход :
            ТО: 1012
            Т1: 411
            T2: 602

            Оплачено: 5006,00 руб.
            """.trimIndent(),
            formatter.chairmanMessage(profile, calculation()),
        )
    }

    private fun calculation(): ReadingCalculation {
        val previous = MeterReading(1, 42, LocalDate.of(2025, 12, 3), 744_000, 295_100, tariffs)
        val current = MeterReading(2, 42, LocalDate.of(2026, 4, 26), 785_056, 355_253, tariffs)
        return calculator.calculate(current, previous)
    }
}

