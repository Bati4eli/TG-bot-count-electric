package ru.sokolniki.electricity.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Единый формат календарных дат, отображаемых пользователю в сообщениях бота. */
object UserDateFormat {
    /** Преобразует дату в вид `dd.MM.yyyy`. */
    fun format(date: LocalDate): String = FORMATTER.format(date)

    private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
}
