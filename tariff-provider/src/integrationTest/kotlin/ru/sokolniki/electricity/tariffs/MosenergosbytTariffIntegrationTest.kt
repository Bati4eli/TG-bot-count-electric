package ru.sokolniki.electricity.tariffs

import kotlin.test.Test
import kotlin.test.assertTrue

/** Локальная интеграционная проверка реального запроса к калькулятору Мосэнергосбыта. */
class MosenergosbytTariffIntegrationTest {
    @Test
    fun `получает актуальные тарифы Т1 и Т2`() {
        val official = MosenergosbytTariffProvider().fetch()

        assertTrue(official.tariffs.t1Cents > 0, "Тариф Т1 должен быть положительным.")
        assertTrue(official.tariffs.t2Cents > 0, "Тариф Т2 должен быть положительным.")
        println("Мосэнергосбыт: Т1 ${official.tariffs.t1Rubles} ₽, Т2 ${official.tariffs.t2Rubles} ₽")
    }
}
