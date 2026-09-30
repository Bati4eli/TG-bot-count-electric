package ru.sokolniki.electricity.persistence

import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.util.Properties

/** Управляет общими ресурсами SQLite: драйвером, соединениями, транзакциями и миграциями схемы. */
class SqliteDatabase(
    private val databasePath: Path,
    private val clock: Clock,
) {
    init {
        Class.forName("org.sqlite.JDBC")
    }

    /** Создаёт все таблицы и индексы SQLite, если они отсутствуют. */
    fun migrate() {
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                SCHEMA_STATEMENTS.forEach(statement::execute)
            }
        }
    }

    /** Открывает соединение для одного запроса и гарантированно закрывает его. */
    fun <T> query(block: (Connection) -> T): T = connect().use(block)

    /** Выполняет блок в атомарной транзакции с откатом при любой ошибке. */
    fun <T> transaction(block: (Connection) -> T): T = connect().use { connection ->
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    /** Возвращает текущее время общего для приложения [Clock] в миллисекундах Unix-времени. */
    fun now(): Long = clock.instant().toEpochMilli()

    /** Создаёт новое SQLite-соединение с включённой проверкой внешних ключей. */
    private fun connect(): Connection {
        val properties = Properties().apply { setProperty("foreign_keys", "true") }
        return java.sql.DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}", properties)
    }

    private companion object {
        val SCHEMA_STATEMENTS = listOf(
            """
            CREATE TABLE IF NOT EXISTS user_profiles (
                telegram_user_id INTEGER PRIMARY KEY,
                chat_id INTEGER NOT NULL,
                plot_number TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS active_tariffs (
                telegram_user_id INTEGER PRIMARY KEY,
                t1_cents INTEGER NOT NULL CHECK (t1_cents > 0),
                t2_cents INTEGER NOT NULL CHECK (t2_cents > 0),
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (telegram_user_id) REFERENCES user_profiles(telegram_user_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS readings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                telegram_user_id INTEGER NOT NULL,
                reading_date TEXT NOT NULL,
                t1_hundredths INTEGER NOT NULL CHECK (t1_hundredths >= 0),
                t2_hundredths INTEGER NOT NULL CHECK (t2_hundredths >= 0),
                tariff_t1_cents INTEGER NOT NULL CHECK (tariff_t1_cents > 0),
                tariff_t2_cents INTEGER NOT NULL CHECK (tariff_t2_cents > 0),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (telegram_user_id) REFERENCES user_profiles(telegram_user_id)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_readings_user_id ON readings(telegram_user_id, id DESC)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_readings_user_date ON readings(telegram_user_id, reading_date)",
            """
            CREATE TABLE IF NOT EXISTS tariff_alerts (
                telegram_user_id INTEGER PRIMARY KEY,
                tariff_t1_cents INTEGER NOT NULL,
                tariff_t2_cents INTEGER NOT NULL,
                sent_at INTEGER NOT NULL,
                FOREIGN KEY (telegram_user_id) REFERENCES user_profiles(telegram_user_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS reading_reminders (
                telegram_user_id INTEGER PRIMARY KEY,
                reminder_month TEXT NOT NULL,
                sent_at INTEGER NOT NULL,
                FOREIGN KEY (telegram_user_id) REFERENCES user_profiles(telegram_user_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS conversation_states (
                telegram_user_id INTEGER PRIMARY KEY,
                step TEXT NOT NULL,
                draft_value INTEGER,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS bot_state (
                state_key TEXT PRIMARY KEY,
                state_value INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}

/** Общая база для репозиториев SQLite с единым механизмом запросов и транзакций. */
abstract class BaseJdbcRepository(protected val database: SqliteDatabase) {
    /** Выполняет запрос специализированного репозитория. */
    protected fun <T> query(block: (Connection) -> T): T = database.query(block)

    /** Выполняет атомарную операцию специализированного репозитория. */
    protected fun <T> transaction(block: (Connection) -> T): T = database.transaction(block)

    /** Возвращает текущее время базы данных. */
    protected fun now(): Long = database.now()
}
