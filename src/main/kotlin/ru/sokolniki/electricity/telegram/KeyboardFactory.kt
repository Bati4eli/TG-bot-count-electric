package ru.sokolniki.electricity.telegram

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import ru.sokolniki.electricity.domain.Tariffs
import java.math.BigDecimal

/** Хранит текстовые подписи, используемые в клавиатурах и обработке команд. */
object ButtonText {
    const val ADD_READING = "➕ Внести показания"
    const val TARIFFS = "⚙️ Изменить тарифы"
    const val OFFICIAL_TARIFFS = "💡 Актуальные тарифы"
    const val EDIT_LAST = "✏️ Изменить показание"
    const val LAST_READING = "📊 Последнее показание"
    const val DOWNLOAD_HISTORY = "📥 Скачать историю"
    const val UPLOAD_HISTORY = "📤 Загрузить историю"
    const val BANK = "📋 Текст для банка"
    const val CHAIRMAN = "📋 Текст для чата"
    const val PLOT = "🏡 Изменить участок"
    const val MENU = "◀️ Меню"
}

/** Создаёт обычные и встроенные клавиатуры Telegram, используемые в диалоге с ботом. */
object KeyboardFactory {
    fun main(): JsonObject = replyKeyboard(
        listOf(
            listOf(ButtonText.ADD_READING, ButtonText.EDIT_LAST),
            listOf(ButtonText.TARIFFS, ButtonText.OFFICIAL_TARIFFS),
            listOf(ButtonText.DOWNLOAD_HISTORY, ButtonText.UPLOAD_HISTORY),
            listOf(ButtonText.BANK, ButtonText.CHAIRMAN),
            listOf(ButtonText.PLOT, ButtonText.MENU),
        ),
    )

    fun setup(): JsonObject = replyKeyboard(listOf(listOf(ButtonText.MENU)))

    fun editLast(): JsonObject = buildJsonObject {
        put("inline_keyboard", buildJsonArray {
            add(callbackRow("Показание Т1", "edit:reading_t1", "Показание Т2", "edit:reading_t2"))
            add(callbackRow("Тариф Т1", "edit:tariff_t1", "Тариф Т2", "edit:tariff_t2"))
            add(buildJsonArray {
                add(buildJsonObject {
                    put("text", JsonPrimitive("Отмена"))
                    put("callback_data", JsonPrimitive("edit:cancel"))
                })
            })
        })
    }

    fun copyText(text: String, buttonText: String = "Скопировать"): JsonObject {
        require(text.length in 1..256) { "Текст для копирования Telegram должен иметь длину от 1 до 256 символов." }
        return buildJsonObject {
            put("inline_keyboard", buildJsonArray {
                add(buildJsonArray {
                    add(buildJsonObject {
                        put("text", JsonPrimitive(buttonText))
                        put("copy_text", buildJsonObject { put("text", JsonPrimitive(text)) })
                    })
                })
            })
        }
    }

    /** Создаёт явное действие пользователя для применения последней официальной пары тарифов. */
    fun applyRecommendedTariffs(tariffs: Tariffs): JsonObject = buildJsonObject {
        put("inline_keyboard", buildJsonArray {
            add(buildJsonArray {
                add(buildJsonObject {
                    put("text", JsonPrimitive("Применить Т1 ${format(tariffs.t1Cents)} · Т2 ${format(tariffs.t2Cents)}"))
                    put("callback_data", JsonPrimitive("tariffs:apply_recommended"))
                })
            })
        })
    }

    private fun replyKeyboard(rows: List<List<String>>): JsonObject = buildJsonObject {
        put("keyboard", rows.toJsonRows())
        put("resize_keyboard", JsonPrimitive(true))
        put("is_persistent", JsonPrimitive(true))
    }

    private fun List<List<String>>.toJsonRows(): JsonArray = buildJsonArray {
        this@toJsonRows.forEach { row ->
            add(buildJsonArray { row.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun format(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',') + " ₽"

    private fun callbackRow(
        firstText: String,
        firstData: String,
        secondText: String,
        secondData: String,
    ): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("text", JsonPrimitive(firstText))
            put("callback_data", JsonPrimitive(firstData))
        })
        add(buildJsonObject {
            put("text", JsonPrimitive(secondText))
            put("callback_data", JsonPrimitive(secondData))
        })
    }
}

