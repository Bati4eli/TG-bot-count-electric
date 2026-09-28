package ru.sokolniki.electricity.telegram

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyboardFactoryTest {
    @Test
    fun `copy button uses Telegram copy_text action`() {
        val markup = KeyboardFactory.copyText("Текст для банка")
        val button = markup["inline_keyboard"]!!.jsonArray[0].jsonArray[0].jsonObject

        assertEquals("Скопировать", button["text"]!!.jsonPrimitive.content)
        assertEquals("Текст для банка", button["copy_text"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `main keyboard contains history export and import actions`() {
        val rows = KeyboardFactory.main()["keyboard"]!!.jsonArray
            .map { row -> row.jsonArray.map { it.jsonPrimitive.content } }

        assertEquals(true, rows.flatten().contains(ButtonText.DOWNLOAD_HISTORY))
        assertEquals(true, rows.flatten().contains(ButtonText.UPLOAD_HISTORY))
        assertEquals(true, rows.flatten().contains(ButtonText.OFFICIAL_TARIFFS))
    }

    @Test
    fun `recommended tariff button uses explicit callback`() {
        val markup = KeyboardFactory.applyRecommendedTariffs(ru.sokolniki.electricity.domain.Tariffs(773, 332))
        val button = markup["inline_keyboard"]!!.jsonArray[0].jsonArray[0].jsonObject

        assertEquals("tariffs:apply_recommended", button["callback_data"]!!.jsonPrimitive.content)
        assertEquals("Применить Т1 7,73 ₽ · Т2 3,32 ₽", button["text"]!!.jsonPrimitive.content)
    }
}

