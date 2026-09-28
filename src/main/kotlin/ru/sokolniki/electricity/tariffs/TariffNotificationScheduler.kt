package ru.sokolniki.electricity.tariffs

import ru.sokolniki.electricity.telegram.KeyboardFactory
import ru.sokolniki.electricity.telegram.TelegramClient
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Рассылает пользователям бота уведомления об изменениях тарифов после успешного официального запроса. */
class TariffNotificationJob(
    private val updates: TariffUpdateService,
    private val telegram: TelegramClient,
) : Runnable {
    override fun run() {
        try {
            updates.refresh().forEach { alert ->
                try {
                    telegram.sendMessage(
                        alert.target.profile.chatId,
                        alertText(alert),
                        KeyboardFactory.applyRecommendedTariffs(alert.official.tariffs),
                        parseMode = "HTML",
                    )
                    updates.markAlertSent(alert.target.profile.telegramUserId, alert.official.tariffs)
                } catch (error: Exception) {
                    System.err.println("Не удалось уведомить пользователя ${alert.target.profile.telegramUserId} о тарифах: ${error.message}")
                }
            }
        } catch (error: Exception) {
            System.err.println("Не удалось обновить официальные тарифы: ${error.message}")
        }
    }

    private fun alertText(alert: TariffAlert): String {
        val actual = alert.target.tariffs
        val headline = if (alert.firstSuccessfulLookup || alert.officialChanged) {
            "❗ <b>На сайте Мосэнергосбыта произошло обновление стоимости тарифа.</b>"
        } else if (actual != alert.official.tariffs) {
            "⚠️ <b>Ваши вручную введённые тарифы отличаются от актуальных.</b>"
        } else {
            "💡 <b>Актуальные тарифы Мосэнергосбыта.</b>"
        }
        val actualText = actual?.let {
            "Ваши: <code>Т1 ${format(it.t1Cents)} ₽ · Т2 ${format(it.t2Cents)} ₽</code>\n"
        } ?: "Ваши тарифы ещё не настроены.\n"
        val source = "<a href=\"${alert.official.sourceUrl}\">Официальный калькулятор Мосэнергосбыта</a>"
        return "$headline\n\n" +
            "$actualText" +
            "Рекомендуемые: <code>Т1 ${format(alert.official.tariffs.t1Cents)} ₽ · Т2 ${format(alert.official.tariffs.t2Cents)} ₽</code>\n" +
            "Параметры: Московская область · сельский тариф · 2 тарифа.\n\n" +
            "$source\n\n" +
            "Кнопка ниже применит тарифы только для следующих показаний. История не изменится."
    }

    private fun format(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',')
}

/** Запускает задачу уведомлений при старте, а затем каждый день в 03:00 по московскому времени. */
class DailyTariffNotificationScheduler(
    private val job: Runnable,
    private val clock: Clock,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "official-tariff-refresh").apply { isDaemon = true }
    }

    /** Запускает неблокирующее первоначальное обновление и планирует последующие ежедневные проверки. */
    fun start() {
        executor.execute(job)
        executor.scheduleAtFixedRate(job, delayUntilNextThreeAm(), Duration.ofDays(1).toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun delayUntilNextThreeAm(): Long {
        val now = ZonedDateTime.now(clock)
        val todayAtThree = now.toLocalDate().atTime(3, 0).atZone(now.zone)
        val next = if (todayAtThree.isAfter(now)) todayAtThree else todayAtThree.plusDays(1)
        return Duration.between(now, next).toMillis()
    }
}
