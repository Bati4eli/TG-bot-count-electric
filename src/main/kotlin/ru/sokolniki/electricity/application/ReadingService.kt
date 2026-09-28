package ru.sokolniki.electricity.application

import ru.sokolniki.electricity.domain.EditableField
import ru.sokolniki.electricity.domain.ElectricityCalculator
import ru.sokolniki.electricity.domain.LatestReading
import ru.sokolniki.electricity.domain.MeterReading
import ru.sokolniki.electricity.domain.ReadingCalculation
import ru.sokolniki.electricity.domain.Tariffs
import ru.sokolniki.electricity.persistence.JdbcRepository
import java.time.Clock
import java.time.LocalDate

class ReadingService(
    private val repository: JdbcRepository,
    private val calculator: ElectricityCalculator,
    private val clock: Clock,
) {
    fun addReading(userId: Long, t1Hundredths: Long, t2Hundredths: Long): ReadingCalculation {
        val tariffs = requireNotNull(repository.findActiveTariffs(userId)) {
            "Сначала укажите тарифы Т1 и Т2."
        }
        val previous = repository.findLatestReading(userId)?.current
        val draft = MeterReading(
            id = 0,
            telegramUserId = userId,
            date = LocalDate.now(clock),
            t1Hundredths = t1Hundredths,
            t2Hundredths = t2Hundredths,
            tariffs = tariffs,
        )
        calculator.calculate(draft, previous)
        val saved = repository.createReading(draft)
        return calculator.calculate(saved, previous)
    }

    fun latestCalculation(userId: Long): ReadingCalculation? = repository.findLatestReading(userId)?.toCalculation()

    fun updateLatest(
        userId: Long,
        field: EditableField,
        newValue: Long,
    ): ReadingCalculation? {
        val before = repository.findLatestReading(userId) ?: return null
        val synchronizeTariffs = field == EditableField.TARIFF_T1 || field == EditableField.TARIFF_T2
        val candidate = applyChange(before.current, field, newValue)
        calculator.calculate(candidate, before.previous)
        val updated = repository.updateLatestReading(userId, synchronizeTariffs) { current ->
            require(current.id == before.current.id) { "Последнее показание уже изменилось. Повторите действие." }
            applyChange(current, field, newValue)
        } ?: return null
        return updated.toCalculation()
    }

    private fun applyChange(current: MeterReading, field: EditableField, newValue: Long): MeterReading = when (field) {
        EditableField.READING_T1 -> current.copy(t1Hundredths = newValue)
        EditableField.READING_T2 -> current.copy(t2Hundredths = newValue)
        EditableField.TARIFF_T1 -> current.copy(tariffs = Tariffs(newValue, current.tariffs.t2Cents))
        EditableField.TARIFF_T2 -> current.copy(tariffs = Tariffs(current.tariffs.t1Cents, newValue))
    }

    private fun LatestReading.toCalculation(): ReadingCalculation = calculator.calculate(current, previous)
}

