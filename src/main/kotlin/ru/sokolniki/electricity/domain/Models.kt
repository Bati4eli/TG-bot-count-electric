package ru.sokolniki.electricity.domain

import java.math.BigDecimal
import java.time.LocalDate

/** Идентифицирует пользователя Telegram и закреплённый за ним участок. */
data class UserProfile(
    val telegramUserId: Long,
    val chatId: Long,
    val plotNumber: String,
)

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

/** Хранит показания счётчика на дату вместе с действовавшими для них тарифами. */
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

/** Представляет одну проверенную строку истории, импортируемой из Excel. */
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

/** Содержит расход электроэнергии по каждой тарифной зоне между двумя показаниями. */
data class Consumption(
    val t1Kwh: BigDecimal,
    val t2Kwh: BigDecimal,
) {
    val totalKwh: BigDecimal get() = t1Kwh + t2Kwh
}

/** Объединяет показание с рассчитанным расходом и округлённой суммой к оплате. */
data class ReadingCalculation(
    val current: MeterReading,
    val previous: MeterReading?,
    val consumption: Consumption,
    val paymentRubles: Long,
)

/** Хранит последнее показание и показание, непосредственно ему предшествующее. */
data class LatestReading(
    val current: MeterReading,
    val previous: MeterReading?,
)

/** Перечисляет сохраняемые состояния диалога бота с пользователем. */
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

/** Хранит текущий шаг диалога и необязательное промежуточное числовое значение. */
data class ConversationState(
    val step: ConversationStep,
    val draftValue: Long? = null,
)

/** Перечисляет части последнего показания, которые пользователь может изменить. */
enum class EditableField {
    READING_T1,
    READING_T2,
    TARIFF_T1,
    TARIFF_T2,
}

