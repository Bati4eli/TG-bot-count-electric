package ru.sokolniki.electricity

import ru.sokolniki.electricity.application.ReadingService
import ru.sokolniki.electricity.config.AppConfigLoader
import ru.sokolniki.electricity.domain.ElectricityCalculator
import ru.sokolniki.electricity.domain.MessageFormatter
import ru.sokolniki.electricity.persistence.JdbcRepository
import ru.sokolniki.electricity.telegram.BotController
import ru.sokolniki.electricity.telegram.BotRunner
import ru.sokolniki.electricity.telegram.TelegramClient
import java.nio.file.Files
import java.time.Clock
import java.time.ZoneId

fun main() {
    val config = AppConfigLoader.load()
    config.databasePath.toAbsolutePath().parent?.let(Files::createDirectories)

    val clock = Clock.system(ZoneId.of("Europe/Moscow"))
    val repository = JdbcRepository(config.databasePath, clock)
    repository.migrate()

    val telegram = TelegramClient(config.botToken)
    val controller = BotController(
        telegram = telegram,
        repository = repository,
        readings = ReadingService(repository, ElectricityCalculator(), clock),
        messages = MessageFormatter(),
    )
    BotRunner(telegram, controller, repository).runForever()
}

