package ru.sokolniki.electricity

import ru.sokolniki.electricity.application.ReadingService
import ru.sokolniki.electricity.config.AppConfigLoader
import ru.sokolniki.electricity.domain.BuildInfo
import ru.sokolniki.electricity.domain.ElectricityCalculator
import ru.sokolniki.electricity.domain.MessageFormatter
import ru.sokolniki.electricity.history.ExcelHistoryService
import ru.sokolniki.electricity.persistence.JdbcRepository
import ru.sokolniki.electricity.reminders.MonthlyReadingReminderScheduler
import ru.sokolniki.electricity.reminders.ReadingReminderJob
import ru.sokolniki.electricity.telegram.BotController
import ru.sokolniki.electricity.telegram.BotRunner
import ru.sokolniki.electricity.telegram.TelegramClient
import ru.sokolniki.electricity.tariffs.DailyTariffNotificationScheduler
import ru.sokolniki.electricity.tariffs.RemoteTariffServiceClient
import ru.sokolniki.electricity.tariffs.TariffNotificationJob
import ru.sokolniki.electricity.tariffs.TariffUpdateService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId

fun main() {
    println(BuildInfo.startupBanner("ОСНОВНОЕ ПРИЛОЖЕНИЕ"))
    println("[bot] Запуск основного приложения.")
    val config = AppConfigLoader.load()
    config.databasePath.toAbsolutePath().parent?.let(Files::createDirectories)

    val clock = Clock.system(ZoneId.of("Europe/Moscow"))
    val repository = JdbcRepository(config.databasePath, clock)
    repository.migrate()
    println("[bot] SQLite готова: ${config.databasePath.toAbsolutePath()}.")

    val telegram = TelegramClient(config.botToken)
    val tariffSource = config.tariffServiceUri?.let { uri ->
        RemoteTariffServiceClient(uri, requireNotNull(config.tariffServiceToken))
    }
    val tariffUpdates = TariffUpdateService(repository, tariffSource, clock)
    val controller = BotController(
        telegram = telegram,
        repository = repository,
        readings = ReadingService(repository, ElectricityCalculator(), clock),
        messages = MessageFormatter(),
        history = ExcelHistoryService(Path.of("шаблон.xlsx")),
        tariffUpdates = tariffUpdates,
    )
    tariffSource?.let {
        println("[bot] Подключён сервис официальных тарифов: ${config.tariffServiceUri}.")
        DailyTariffNotificationScheduler(
            job = TariffNotificationJob(tariffUpdates, telegram),
            clock = clock,
        ).start()
    } ?: println("[bot] Сервис официальных тарифов не настроен; используются сохранённые данные.")
    MonthlyReadingReminderScheduler(
        job = ReadingReminderJob(repository, telegram, clock),
        clock = clock,
    ).start()
    Runtime.getRuntime().addShutdownHook(Thread {
        println("[bot] Получен сигнал завершения. Основное приложение остановлено.")
    })
    println("[bot] Telegram long polling запущен.")
    BotRunner(telegram, controller, repository).runForever()
}

