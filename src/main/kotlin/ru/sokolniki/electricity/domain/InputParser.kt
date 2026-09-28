package ru.sokolniki.electricity.domain

import java.math.BigDecimal
import java.math.RoundingMode

object InputParser {
    private val decimalPattern = Regex("\\d+(?:[,.]\\d{1,2})?")
    private const val MAX_READING_HUNDREDTHS = 99_999_999_999L
    private const val MAX_TARIFF_CENTS = 9_999_999L

    fun plotNumber(text: String): String {
        val value = text.trim()
        require(value.length in 1..32) { "Номер участка должен содержать от 1 до 32 символов." }
        require(value.none { it.code in 0..31 || it.code == 127 }) { "Номер участка содержит недопустимые символы." }
        return value
    }

    fun tariffCents(text: String): Long = parsePositiveHundredths(text, "Тариф")

    fun readingHundredths(text: String): Long {
        val normalized = text.trim().replace(" ", "")
        require(decimalPattern.matches(normalized)) {
            "Введите показание числом, например 10305 или 10305,25."
        }
        val value = try {
            BigDecimal(normalized.replace(',', '.'))
                .setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2)
                .longValueExact()
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Показание слишком велико.")
        }
        require(value <= MAX_READING_HUNDREDTHS) { "Показание слишком велико." }
        return value
    }

    private fun parsePositiveHundredths(text: String, fieldName: String): Long {
        val normalized = text.trim().replace(" ", "")
        require(decimalPattern.matches(normalized)) {
            "$fieldName введите числом, например 7,33."
        }
        val value = try {
            BigDecimal(normalized.replace(',', '.'))
                .setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2)
                .longValueExact()
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("$fieldName слишком велик.")
        }
        require(value > 0) { "$fieldName должен быть больше нуля." }
        require(value <= MAX_TARIFF_CENTS) { "$fieldName слишком велик." }
        return value
    }
}

