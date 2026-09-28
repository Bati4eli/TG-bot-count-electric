package ru.sokolniki.electricity.history

import org.apache.poi.ss.usermodel.WorkbookFactory
import ru.sokolniki.electricity.domain.MeterReading
import ru.sokolniki.electricity.domain.Tariffs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExcelHistoryServiceTest {
    private val service = ExcelHistoryService(Path.of("шаблон.xlsx"))

    @Test
    fun `exports template and reads history back`() {
        val exported = service.export(
            listOf(
                reading("2026-07-01", 10_000, 20_000, 733, 332),
                reading("2026-08-01", 10_150, 20_200, 733, 345),
            ),
        )

        val imported = service.import(exported)

        assertEquals(2, imported.size)
        assertEquals(LocalDate.parse("2026-07-01"), imported[0].date)
        assertEquals(10_150, imported[1].t1Hundredths)
        assertEquals(20_200, imported[1].t2Hundredths)
        assertEquals(345, imported[1].tariffs.t2Cents)
    }

    @Test
    fun `rejects history where a meter reading does not increase`() {
        val exported = service.export(
            listOf(
                reading("2026-07-01", 10_000, 20_000, 733, 332),
                reading("2026-08-01", 10_150, 20_200, 733, 332),
            ),
        )
        val invalid = mutate(exported) { workbook ->
            workbook.getSheet("ПОКАЗАНИЯ СЧЕТЧИКОВ").getRow(2).getCell(1).setCellValue(100.00)
        }

        val error = assertFailsWith<IllegalArgumentException> { service.import(invalid) }

        assertEquals("Строка 3: показание Т1 должно быть больше предыдущего.", error.message)
    }

    @Test
    fun `rejects a gap in populated history rows`() {
        val exported = service.export(
            listOf(
                reading("2026-07-01", 10_000, 20_000, 733, 332),
                reading("2026-08-01", 10_150, 20_200, 733, 332),
            ),
        )
        val invalid = mutate(exported) { workbook ->
            workbook.getSheet("ПОКАЗАНИЯ СЧЕТЧИКОВ").getRow(1).getCell(2).setBlank()
        }

        val error = assertFailsWith<IllegalArgumentException> { service.import(invalid) }

        assertEquals("Строка 2: не заполнено поле «показания Т2».", error.message)
    }

    private fun reading(
        date: String,
        t1: Long,
        t2: Long,
        tariffT1: Long,
        tariffT2: Long,
    ) = MeterReading(
        id = 0,
        telegramUserId = 1,
        date = LocalDate.parse(date),
        t1Hundredths = t1,
        t2Hundredths = t2,
        tariffs = Tariffs(tariffT1, tariffT2),
    )

    private fun mutate(bytes: ByteArray, change: (org.apache.poi.ss.usermodel.Workbook) -> Unit): ByteArray =
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { workbook ->
            change(workbook)
            ByteArrayOutputStream().use { output ->
                workbook.write(output)
                output.toByteArray()
            }
        }
}
