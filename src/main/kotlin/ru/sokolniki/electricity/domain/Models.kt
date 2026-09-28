package ru.sokolniki.electricity.domain

import java.math.BigDecimal
import java.time.LocalDate

/** Identifies a Telegram user and their assigned garden plot. */
data class UserProfile(
    val telegramUserId: Long,
    val chatId: Long,
    val plotNumber: String,
)

/** Stores T1 and T2 electricity prices in kopecks per kWh. */
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

/** Stores one dated meter snapshot with the tariffs that applied to it. */
data class MeterReading(
    val id: Long,
    val telegramUserId: Long,
    val date: LocalDate,
    val t1Hundredths: Long,
    val t2Hundredths: Long,
    val tariffs: Tariffs,
) {
    init {
        require(t1Hundredths >= 0) { "Показание Т1 не может быть отрицательным." }
        require(t2Hundredths >= 0) { "Показание Т2 не может быть отрицательным." }
    }

    val t1Kwh: BigDecimal get() = BigDecimal.valueOf(t1Hundredths, 2)
    val t2Kwh: BigDecimal get() = BigDecimal.valueOf(t2Hundredths, 2)
}

/** Represents one validated row from the Excel history import. */
data class HistoryEntry(
    val date: LocalDate,
    val t1Hundredths: Long,
    val t2Hundredths: Long,
    val tariffs: Tariffs,
) {
    init {
        require(t1Hundredths >= 0) { "Показание Т1 не может быть отрицательным." }
        require(t2Hundredths >= 0) { "Показание Т2 не может быть отрицательным." }
    }
}

/** Contains energy consumed in each tariff zone between two readings. */
data class Consumption(
    val t1Kwh: BigDecimal,
    val t2Kwh: BigDecimal,
) {
    val totalKwh: BigDecimal get() = t1Kwh + t2Kwh
}

/** Combines a reading with its calculated consumption and rounded payment. */
data class ReadingCalculation(
    val current: MeterReading,
    val previous: MeterReading?,
    val consumption: Consumption,
    val paymentRubles: Long,
)

/** Holds the most recent reading and the reading immediately before it. */
data class LatestReading(
    val current: MeterReading,
    val previous: MeterReading?,
)

/** Enumerates the bot dialogue states persisted for a user. */
enum class ConversationStep {
    IDLE,
    SETUP_PLOT,
    EDIT_PLOT,
    SETUP_TARIFF_T1,
    SETUP_TARIFF_T2,
    TARIFF_T1,
    TARIFF_T2,
    READING_T1,
    READING_T2,
    EDIT_READING_T1,
    EDIT_READING_T2,
    EDIT_TARIFF_T1,
    EDIT_TARIFF_T2,
    IMPORT_HISTORY,
}

/** Stores the current dialogue step and an optional intermediate numeric value. */
data class ConversationState(
    val step: ConversationStep,
    val draftValue: Long? = null,
)

/** Enumerates the parts of the latest reading that a user may edit. */
enum class EditableField {
    READING_T1,
    READING_T2,
    TARIFF_T1,
    TARIFF_T2,
}

