package ru.sokolniki.electricity.telegram

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

object ButtonText {
    const val ADD_READING = "➕ Внести показания"
    const val TARIFFS = "⚙️ Тарифы"
    const val EDIT_LAST = "✏️ Изменить последнее"
    const val LAST_READING = "📊 Последнее показание"
    const val BANK = "📋 Банк"
    const val CHAIRMAN = "📋 Председатель"
    const val PLOT = "🏡 Участок"
    const val MENU = "◀️ Меню"
}

object KeyboardFactory {
    fun main(): JsonObject = replyKeyboard(
        listOf(
            listOf(ButtonText.ADD_READING, ButtonText.TARIFFS),
            listOf(ButtonText.EDIT_LAST, ButtonText.LAST_READING),
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

    fun copyText(text: String): JsonObject {
        require(text.length in 1..256) { "Текст для копирования Telegram должен иметь длину от 1 до 256 символов." }
        return buildJsonObject {
            put("inline_keyboard", buildJsonArray {
                add(buildJsonArray {
                    add(buildJsonObject {
                        put("text", JsonPrimitive("Скопировать"))
                        put("copy_text", buildJsonObject { put("text", JsonPrimitive(text)) })
                    })
                })
            })
        }
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

