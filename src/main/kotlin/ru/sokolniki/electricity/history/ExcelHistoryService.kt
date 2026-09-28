package ru.sokolniki.electricity.history

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import ru.sokolniki.electricity.domain.HistoryEntry
import ru.sokolniki.electricity.domain.InputParser
import ru.sokolniki.electricity.domain.MeterReading
import ru.sokolniki.electricity.domain.Tariffs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/** Imports validated personal history from, and exports it to, the supported Excel template. */
class ExcelHistoryService(
    private val templatePath: Path,
) {
    fun export(readings: List<MeterReading>): ByteArray = openTemplate().use { workbook ->
        val sheet = workbook.getSheet(SHEET_NAME)
            ?: error("В шаблоне отсутствует лист «$SHEET_NAME».")
        validateHeaders(sheet.getRow(HEADER_ROW_INDEX))

        readings.forEachIndexed { index, reading ->
            val row = rowForWriting(sheet, index + FIRST_DATA_ROW_INDEX)
            row.getCell(DATE_COLUMN, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(reading.date)
            row.getCell(T1_COLUMN, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(reading.t1Kwh.toDouble())
            row.getCell(T2_COLUMN, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(reading.t2Kwh.toDouble())
            row.getCell(TARIFF_T1_COLUMN, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(reading.tariffs.t1Rubles.toDouble())
            row.getCell(TARIFF_T2_COLUMN, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(reading.tariffs.t2Rubles.toDouble())
        }
        ByteArrayOutputStream().use { output ->
            workbook.write(output)
            output.toByteArray()
        }
    }

    fun import(bytes: ByteArray): List<HistoryEntry> {
        require(bytes.isNotEmpty()) { "Получен пустой файл." }
        try {
            WorkbookFactory.create(ByteArrayInputStream(bytes)).use { workbook ->
                require(workbook.numberOfSheets == 1) { "Нужен файл из шаблона: в нём должен быть один лист." }
                val sheet = workbook.getSheet(SHEET_NAME)
                    ?: throw IllegalArgumentException("Не найден лист «$SHEET_NAME». Скачайте новый файл из бота.")
                validateHeaders(sheet.getRow(HEADER_ROW_INDEX))

                val entries = mutableListOf<HistoryEntry>()
                var blankRowSeen = false
                for (rowIndex in FIRST_DATA_ROW_INDEX..sheet.lastRowNum) {
                    val row = sheet.getRow(rowIndex)
                    if (row != null && rowHasValues(row)) {
                        require(!blankRowSeen) {
                            "Строка ${rowIndex + 1}: обнаружен пропуск между заполненными строками."
                        }
                        entries += entryFrom(row, rowIndex + 1)
                    } else {
                        blankRowSeen = true
                    }
                }
                require(entries.isNotEmpty()) { "В файле нет ни одного заполненного показания." }
                validateSequence(entries)
                return entries
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Не удалось прочитать Excel-файл. Загрузите файл, скачанный из бота.")
        }
    }

    private fun openTemplate(): Workbook {
        require(Files.isRegularFile(templatePath)) { "Не найден файл шаблона истории: $templatePath" }
        return WorkbookFactory.create(Files.newInputStream(templatePath))
    }

    private fun validateHeaders(row: Row?) {
        requireNotNull(row) { "В файле отсутствует строка заголовков." }
        HEADERS.forEachIndexed { column, expected ->
            val actual = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)
                ?.takeIf { it.cellType == CellType.STRING }
                ?.stringCellValue
                ?.trim()
                ?.lowercase()
            require(actual == expected) { "Столбец ${column + 1} не соответствует шаблону." }
        }
    }

    private fun rowForWriting(sheet: org.apache.poi.ss.usermodel.Sheet, rowIndex: Int): Row {
        val existing = sheet.getRow(rowIndex)
        if (existing != null) return existing

        val created = sheet.createRow(rowIndex)
        sheet.getRow(FIRST_DATA_ROW_INDEX)?.let { source ->
            created.height = source.height
            for (column in 0 until COLUMN_COUNT) {
                val sourceCell = source.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL) ?: continue
                created.createCell(column).cellStyle = sourceCell.cellStyle
            }
        }
        return created
    }

    private fun rowHasValues(row: Row): Boolean = (0 until COLUMN_COUNT).any { column ->
        val cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL) ?: return@any false
        when (cell.cellType) {
            CellType.BLANK -> false
            CellType.STRING -> cell.stringCellValue.isNotBlank()
            else -> true
        }
    }

    private fun entryFrom(row: Row, rowNumber: Int): HistoryEntry = HistoryEntry(
        date = readDate(requiredCell(row, DATE_COLUMN, rowNumber, "дата"), rowNumber),
        t1Hundredths = readReading(requiredCell(row, T1_COLUMN, rowNumber, "показания Т1"), rowNumber, "показания Т1"),
        t2Hundredths = readReading(requiredCell(row, T2_COLUMN, rowNumber, "показания Т2"), rowNumber, "показания Т2"),
        tariffs = Tariffs(
            readTariff(requiredCell(row, TARIFF_T1_COLUMN, rowNumber, "тариф Т1"), rowNumber, "тариф Т1"),
            readTariff(requiredCell(row, TARIFF_T2_COLUMN, rowNumber, "тариф Т2"), rowNumber, "тариф Т2"),
        ),
    )

    private fun requiredCell(row: Row, column: Int, rowNumber: Int, field: String): Cell =
        row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)
            ?.takeUnless { it.cellType == CellType.BLANK || (it.cellType == CellType.STRING && it.stringCellValue.isBlank()) }
            ?: throw IllegalArgumentException("Строка $rowNumber: не заполнено поле «$field».")

    private fun readDate(cell: Cell, rowNumber: Int): LocalDate {
        require(cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            "Строка $rowNumber: дата должна быть датой Excel, а не текстом."
        }
        return DateUtil.getLocalDateTime(cell.numericCellValue).toLocalDate()
    }

    private fun readReading(cell: Cell, rowNumber: Int, field: String): Long =
        readPositiveOrZero(cell, rowNumber, field) { value -> InputParser.readingHundredths(value) }

    private fun readTariff(cell: Cell, rowNumber: Int, field: String): Long =
        readPositiveOrZero(cell, rowNumber, field) { value -> InputParser.tariffCents(value) }

    private fun readPositiveOrZero(
        cell: Cell,
        rowNumber: Int,
        field: String,
        parser: (String) -> Long,
    ): Long {
        require(cell.cellType == CellType.NUMERIC) { "Строка $rowNumber: «$field» должно быть числом." }
        val value = BigDecimal.valueOf(cell.numericCellValue).stripTrailingZeros().toPlainString()
        return try {
            parser(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Строка $rowNumber: ${error.message}")
        }
    }

    private fun validateSequence(entries: List<HistoryEntry>) {
        entries.zipWithNext().forEachIndexed { index, (previous, current) ->
            val rowNumber = index + FIRST_DATA_ROW_INDEX + 2
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
    }

    private companion object {
        const val SHEET_NAME = "ПОКАЗАНИЯ СЧЕТЧИКОВ"
        const val HEADER_ROW_INDEX = 0
        const val FIRST_DATA_ROW_INDEX = 1
        const val COLUMN_COUNT = 5
        const val DATE_COLUMN = 0
        const val T1_COLUMN = 1
        const val T2_COLUMN = 2
        const val TARIFF_T1_COLUMN = 3
        const val TARIFF_T2_COLUMN = 4
        val HEADERS = listOf("дата", "показания т1", "показания т2", "тариф т1", "тариф т2")
    }
}
