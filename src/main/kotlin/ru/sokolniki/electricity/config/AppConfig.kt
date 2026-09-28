package ru.sokolniki.electricity.config

import java.nio.file.Path
import java.nio.file.Paths

/** Хранит неизменяемые настройки, необходимые для запуска бота. */
data class AppConfig(
    val botToken: String,
    val databasePath: Path,
)

/** Загружает настройки из переменных окружения, не раскрывая секреты в исходном коде. */
object AppConfigLoader {
    fun load(environment: Map<String, String> = System.getenv()): AppConfig {
        val token = environment["BOT_TOKEN"]?.trim().orEmpty()
        require(token.isNotEmpty()) { "Не задана переменная окружения BOT_TOKEN." }
        val databasePath = environment["ELECTRICITY_DB"]
            ?.takeIf { it.isNotBlank() }
            ?.let(Paths::get)
            ?: Paths.get("data", "electricity.db")
        return AppConfig(token, databasePath)
    }
}

