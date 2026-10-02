package ru.sokolniki.electricity.tariffs

import ru.sokolniki.electricity.telegram.KeyboardFactory
import ru.sokolniki.electricity.telegram.TelegramClient
import ru.sokolniki.electricity.domain.Tariffs
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
            println("[bot] Начато обновление официальных тарифов.")
            val alerts = updates.refresh()
            alerts.forEach { alert ->
                try {
                    telegram.sendMessage(
                        alert.target.profile.chatId,
                        alertText(alert),
                        replyMarkup(alert),
                        parseMode = "HTML",
                    )
                    updates.markAlertSent(alert.target.profile.telegramUserId, alert.official.tariffs)
                } catch (error: Exception) {
                    System.err.println("Не удалось уведомить пользователя ${alert.target.profile.telegramUserId} о тарифах: ${error.message}")
                }
            }
            println("[bot] Официальные тарифы обновлены; уведомлений к отправке: ${alerts.size}.")
        } catch (error: Exception) {
            System.err.println("[bot] Не удалось обновить официальные тарифы: ${error.message}")
        }
    }

    /** Подставляет данные уведомления в единый HTML-шаблон для пользователя. */
    private fun alertText(alert: TariffAlert): String {
        val actual = alert.target.tariffs
        val headline = if (alert.firstSuccessfulLookup || alert.officialChanged) {
            "❗ <b>На сайте Мосэнергосбыта произошло обновление стоимости тарифа.</b>"
        } else if (actual != alert.official.tariffs) {
            "⚠️ <b>Ваши вручную введённые тарифы отличаются от актуальных.</b>"
        } else {
            "💡 <b>Актуальные тарифы Мосэнергосбыта.</b>"
        }
        return TariffMessageFormatter.format(actual, alert.official, headline)
    }

    private fun replyMarkup(alert: TariffAlert) = if (TariffMessageFormatter.needsApplication(alert.target.tariffs, alert.official)) {
        KeyboardFactory.applyRecommendedTariffs(alert.official.tariffs)
    } else {
        KeyboardFactory.main()
    }

}

/** Формирует единый HTML-вид для ручного просмотра и фоновых уведомлений о тарифах. */
object TariffMessageFormatter {
    /** Подставляет пользовательские и официальные тарифы в общий шаблон сообщения. */
    fun format(actual: Tariffs?, official: OfficialTariffs, headline: String): String {
        val actualText = actual?.let { "Ваши: <code>Т1 ${formatTariff(it.t1Cents)} ₽ · Т2 ${formatTariff(it.t2Cents)} ₽</code>" }
            ?: "Ваши тарифы ещё не настроены."
        val applicationNote = if (actual == official.tariffs) "" else "\n\nКнопка ниже применит тарифы только для следующих показаний. История не изменится."
        return """
            $headline

            $actualText
            Рекомендуемые: <code>Т1 ${formatTariff(official.tariffs.t1Cents)} ₽ · Т2 ${formatTariff(official.tariffs.t2Cents)} ₽</code>

            <a href="${official.sourceUrl}">Официальный калькулятор Мосэнергосбыта</a>
            <blockquote>Параметры: Московская область · сельский тариф · 2 тарифа.</blockquote>$applicationNote
        """.trimIndent()
    }

    /** Возвращает, нужно ли показать действие применения рекомендации. */
    fun needsApplication(actual: Tariffs?, official: OfficialTariffs): Boolean = actual != official.tariffs

    /** Преобразует тариф из копеек в компактный вид для сообщения. */
    private fun formatTariff(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',')
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
        println("[bot] Планировщик тарифов запущен: сразу и ежедневно в 03:00 (Europe/Moscow).")
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
