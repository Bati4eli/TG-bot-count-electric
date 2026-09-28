package ru.sokolniki.electricity.application

import ru.sokolniki.electricity.domain.EditableField
import ru.sokolniki.electricity.domain.ElectricityCalculator
import ru.sokolniki.electricity.domain.HistoryEntry
import ru.sokolniki.electricity.domain.LatestReading
import ru.sokolniki.electricity.domain.MeterReading
import ru.sokolniki.electricity.domain.ReadingCalculation
import ru.sokolniki.electricity.domain.Tariffs
import ru.sokolniki.electricity.persistence.JdbcRepository
import ru.sokolniki.electricity.persistence.HistoryReplacementResult
import java.time.Clock
import java.time.LocalDate

/**
 * Реализует сценарии добавления, редактирования, экспорта и замены истории показаний пользователя.
 */
class ReadingService(
    private val repository: JdbcRepository,
    private val calculator: ElectricityCalculator,
    private val clock: Clock,
) {
    fun addReading(userId: Long, t1Hundredths: Long, t2Hundredths: Long): ReadingCalculation {
        val tariffs = requireNotNull(repository.findActiveTariffs(userId)) {
            "Сначала укажите тарифы Т1 и Т2."
        }
        val readingDate = LocalDate.now(clock)
        require(!repository.hasReadingOnDate(userId, readingDate)) {
            "Показание за $readingDate уже сохранено. Если нужно исправление, используйте «Изменить последнее»."
        }
        val previous = repository.findLatestReading(userId)?.current
        val draft = MeterReading(
            id = 0,
            telegramUserId = userId,
            date = readingDate,
            t1Hundredths = t1Hundredths,
            t2Hundredths = t2Hundredths,
            tariffs = tariffs,
        )
        calculator.calculate(draft, previous)
        val saved = repository.createReading(draft)
        return calculator.calculate(saved, previous)
    }

    fun latestCalculation(userId: Long): ReadingCalculation? = repository.findLatestReading(userId)?.toCalculation()

    fun historyForExport(userId: Long): List<MeterReading> = repository.findReadings(userId)

    fun totalPaymentRubles(userId: Long): Long = calculateTotal(repository.findReadings(userId))

    fun importedHistoryTotalPaymentRubles(entries: List<HistoryEntry>): Long = calculateTotal(
        entries.map { entry ->
            MeterReading(
                id = 0,
                telegramUserId = 0,
                date = entry.date,
                t1Hundredths = entry.t1Hundredths,
                t2Hundredths = entry.t2Hundredths,
                tariffs = entry.tariffs,
            )
        },
    )

    fun replaceHistory(userId: Long, entries: List<HistoryEntry>): HistoryReplacementResult {
        require(entries.isNotEmpty()) { "В файле должно быть хотя бы одно показание." }
        entries.zipWithNext().forEachIndexed { index, (previous, current) ->
            val rowNumber = index + 3
            require(current.date.isAfter(previous.date)) {
                "Строка $rowNumber: дата должна быть позже даты в предыдущей строке."
            }
            require(current.t1Hundredths > previous.t1Hundredths) {
                "Строка $rowNumber: показание Т1 должно быть больше предыдущего."
            }
            require(current.t2Hundredths > previous.t2Hundredths) {
                "Строка $rowNumber: показание Т2 должно быть больше предыдущего."
            }
        }
        return repository.replaceHistory(userId, entries)
    }

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

    private fun calculateTotal(history: List<MeterReading>): Long {
        var previous: MeterReading? = null
        var total = 0L
        history.forEach { current ->
            total = Math.addExact(total, calculator.calculate(current, previous).paymentRubles)
            previous = current
        }
        return total
    }

    private fun LatestReading.toCalculation(): ReadingCalculation = calculator.calculate(current, previous)
}

