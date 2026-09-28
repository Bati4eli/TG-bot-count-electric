package ru.sokolniki.electricity.config

import java.nio.file.Path
import java.nio.file.Paths

/** Holds immutable settings required to run the bot. */
data class AppConfig(
    val botToken: String,
    val databasePath: Path,
)

/** Loads application settings from environment variables without exposing secrets in source code. */
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

