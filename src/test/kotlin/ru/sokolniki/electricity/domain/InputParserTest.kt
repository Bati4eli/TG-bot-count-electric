package ru.sokolniki.electricity.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InputParserTest {
    @Test
    fun `accepts decimal separators and stores hundredths`() {
        assertEquals(733, InputParser.tariffCents("7,33"))
        assertEquals(733, InputParser.tariffCents("7.33"))
        assertEquals(1_234_050, InputParser.readingHundredths("12340,50"))
    }

    @Test
    fun `rejects negative and overprecise input`() {
        assertFailsWith<IllegalArgumentException> { InputParser.tariffCents("-7,33") }
        assertFailsWith<IllegalArgumentException> { InputParser.readingHundredths("1,234") }
    }
}

