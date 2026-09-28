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
}

