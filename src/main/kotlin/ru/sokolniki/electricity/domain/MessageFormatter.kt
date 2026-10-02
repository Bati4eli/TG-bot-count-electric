package ru.sokolniki.electricity.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** Формирует пользовательские сводки и копируемые сообщения об оплате по рассчитанным показаниям. */
class MessageFormatter {
    fun bankMessage(profile: UserProfile, calculation: ReadingCalculation): String = with(calculation) {
        "Уч.${profile.plotNumber}: эл-во. Расход T1:${whole(consumption.t1Kwh)} " +
            "T2:${whole(consumption.t2Kwh)}. Показания T1:${whole(current.t1Kwh)} " +
            "T2:${whole(current.t2Kwh)}"
    }

    fun chairmanMessage(profile: UserProfile, calculation: ReadingCalculation): String = with(calculation) {
        """
        уч. ${profile.plotNumber}
        Показания счетчиков на ${UserDateFormat.format(current.date)}:
        ТО: ${whole(current.t1Kwh + current.t2Kwh)}
        Т1: ${whole(current.t1Kwh)}
        Т2: ${whole(current.t2Kwh)}

        Расход :
        ТО: ${whole(consumption.totalKwh)}
        Т1: ${whole(consumption.t1Kwh)}
        T2: ${whole(consumption.t2Kwh)}
        """.trimIndent()
    }

    fun readingSummary(calculation: ReadingCalculation): String = with(calculation) {
        """
        📊 Последнее показание
        📅 ${UserDateFormat.format(current.date)}

        ⚡ Показания счётчика
        • Т1: ${kwh(current.t1Kwh)} кВт·ч
        • Т2: ${kwh(current.t2Kwh)} кВт·ч

        📈 Расход за период
        • Т1: ${kwh(consumption.t1Kwh)} кВт·ч
        • Т2: ${kwh(consumption.t2Kwh)} кВт·ч

        💰 Тарифы
        • Т1: ${rublesWithCents(current.tariffs.t1Rubles)} ₽
        • Т2: ${rublesWithCents(current.tariffs.t2Rubles)} ₽

        ✅ К оплате: ${rubles(paymentRubles)} ₽
        """.trimIndent()
    }

    private fun whole(value: BigDecimal): String = value.setScale(0, RoundingMode.HALF_UP).toPlainString()

    private fun kwh(value: BigDecimal): String = value.stripTrailingZeros().toPlainString().replace('.', ',')

    private fun rubles(value: Long): String = "$value,00"

    private fun rublesWithCents(value: BigDecimal): String {
        val normalized = value.setScale(2).toPlainString().replace('.', ',')
        return normalized
    }

}

