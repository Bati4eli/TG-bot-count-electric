package ru.sokolniki.electricity.reminders

import ru.sokolniki.electricity.persistence.JdbcRepository
import ru.sokolniki.electricity.persistence.ReadingReminderTarget
import ru.sokolniki.electricity.domain.UserDateFormat
import ru.sokolniki.electricity.telegram.KeyboardFactory
import ru.sokolniki.electricity.telegram.TelegramClient
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Отправляет одно напоминание пользователям, которые давно не передавали показания. */
class ReadingReminderJob(
    private val repository: JdbcRepository,
    private val telegram: TelegramClient,
    private val clock: Clock,
) : Runnable {
    override fun run() {
        val today = LocalDate.now(clock)
        if (today.dayOfMonth !in REMINDER_DAYS) return

        val reminderMonth = YearMonth.from(today)
        val targets = repository.findUsersNeedingReadingReminder(
            lastAllowedReadingDate = today.minusDays(MAX_DAYS_WITHOUT_READING),
            reminderMonth = reminderMonth,
        )
        println("[bot] Начата рассылка напоминаний о показаниях; получателей: ${targets.size}.")
        targets.forEach { target ->
            try {
                telegram.sendMessage(
                    target.profile.chatId,
                    reminderText(target),
                    KeyboardFactory.main(),
                    parseMode = "HTML",
                )
                repository.markReadingReminderSent(target.profile.telegramUserId, reminderMonth)
            } catch (error: Exception) {
                System.err.println(
                    "[bot] Не удалось отправить напоминание пользователю " +
                        "${target.profile.telegramUserId}: ${error.message}",
                )
            }
        }
        println("[bot] Рассылка напоминаний о показаниях завершена.")
    }

    private fun reminderText(target: ReadingReminderTarget): String {
        val latest = target.latestReadingDate?.let { "Последнее показание: <code>${UserDateFormat.format(it)}</code>." }
            ?: "Показания ещё не передавались."
        return "⏰ <b>Напоминание о показаниях.</b>\n\n" +
            "За последние $MAX_DAYS_WITHOUT_READING дней от вас не было новых показаний.\n" +
            "$latest\n\n" +
            "Пожалуйста, передайте текущие Т1 и Т2 кнопкой «➕ Внести показания»."
    }

    private companion object {
        const val MAX_DAYS_WITHOUT_READING = 25L
        val REMINDER_DAYS = 20..31
    }
}

/** Планирует проверку показаний ежедневно в 12:00 по московскому времени. */
class MonthlyReadingReminderScheduler(
    private val job: Runnable,
    private val clock: Clock,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "reading-reminder").apply { isDaemon = true }
    }

    /** Запускает ежедневную проверку; сама задача рассылает уведомления только с 20 по 31 число. */
    fun start() {
        println("[bot] Планировщик напоминаний запущен: ежедневно в 12:00 (Europe/Moscow), рассылка с 20 по 31 число.")
        executor.scheduleAtFixedRate(job, delayUntilNextNoon(), Duration.ofDays(1).toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun delayUntilNextNoon(): Long {
        val now = ZonedDateTime.now(clock)
        val todayAtNoon = now.toLocalDate().atTime(12, 0).atZone(now.zone)
        val next = if (todayAtNoon.isAfter(now)) todayAtNoon else todayAtNoon.plusDays(1)
        return Duration.between(now, next).toMillis()
    }
}
