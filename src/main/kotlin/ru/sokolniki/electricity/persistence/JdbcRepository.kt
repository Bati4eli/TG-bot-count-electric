package ru.sokolniki.electricity.persistence

import ru.sokolniki.electricity.domain.ConversationState
import ru.sokolniki.electricity.domain.ConversationStep
import ru.sokolniki.electricity.domain.LatestReading
import ru.sokolniki.electricity.domain.MeterReading
import ru.sokolniki.electricity.domain.Tariffs
import ru.sokolniki.electricity.domain.UserProfile
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.util.Properties

class JdbcRepository(
    private val databasePath: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        Class.forName("org.sqlite.JDBC")
    }

    fun migrate() {
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS user_profiles (
                        telegram_user_id INTEGER PRIMARY KEY,
                        chat_id INTEGER NOT NULL,
                        plot_number TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS active_tariffs (
                        telegram_user_id INTEGER PRIMARY KEY,
                        t1_cents INTEGER NOT NULL CHECK (t1_cents > 0),
                        t2_cents INTEGER NOT NULL CHECK (t2_cents > 0),
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY (telegram_user_id) REFERENCES user_profiles(telegram_user_id)
                    )
                    """.trimIndent(),
                )
                statement.execute(
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
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_readings_user_id ON readings(telegram_user_id, id DESC)",
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS conversation_states (
                        telegram_user_id INTEGER PRIMARY KEY,
                        step TEXT NOT NULL,
                        draft_value INTEGER,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS bot_state (
                        state_key TEXT PRIMARY KEY,
                        state_value INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    fun saveProfile(profile: UserProfile) {
        val now = now()
        connect().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO user_profiles(telegram_user_id, chat_id, plot_number, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(telegram_user_id) DO UPDATE SET
                    chat_id = excluded.chat_id,
                    plot_number = excluded.plot_number,
                    updated_at = excluded.updated_at
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, profile.telegramUserId)
                statement.setLong(2, profile.chatId)
                statement.setString(3, profile.plotNumber)
                statement.setLong(4, now)
                statement.setLong(5, now)
                statement.executeUpdate()
            }
        }
    }

    fun updateKnownChat(userId: Long, chatId: Long) {
        connect().use { connection ->
            connection.prepareStatement(
                "UPDATE user_profiles SET chat_id = ?, updated_at = ? WHERE telegram_user_id = ?",
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.setLong(2, now())
                statement.setLong(3, userId)
                statement.executeUpdate()
            }
        }
    }

    fun findProfile(userId: Long): UserProfile? = connect().use { connection ->
        connection.prepareStatement(
            "SELECT telegram_user_id, chat_id, plot_number FROM user_profiles WHERE telegram_user_id = ?",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    UserProfile(
                        telegramUserId = resultSet.getLong("telegram_user_id"),
                        chatId = resultSet.getLong("chat_id"),
                        plotNumber = resultSet.getString("plot_number"),
                    )
                } else {
                    null
                }
            }
        }
    }

    fun saveActiveTariffs(userId: Long, tariffs: Tariffs) {
        connect().use { connection ->
            saveActiveTariffs(connection, userId, tariffs)
        }
    }

    fun findActiveTariffs(userId: Long): Tariffs? = connect().use { connection ->
        connection.prepareStatement(
            "SELECT t1_cents, t2_cents FROM active_tariffs WHERE telegram_user_id = ?",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    Tariffs(resultSet.getLong("t1_cents"), resultSet.getLong("t2_cents"))
                } else {
                    null
                }
            }
        }
    }

    fun saveState(userId: Long, state: ConversationState) {
        connect().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO conversation_states(telegram_user_id, step, draft_value, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(telegram_user_id) DO UPDATE SET
                    step = excluded.step,
                    draft_value = excluded.draft_value,
                    updated_at = excluded.updated_at
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, state.step.name)
                state.draftValue?.let { statement.setLong(3, it) } ?: statement.setNull(3, java.sql.Types.INTEGER)
                statement.setLong(4, now())
                statement.executeUpdate()
            }
        }
    }

    fun stateFor(userId: Long): ConversationState = connect().use { connection ->
        connection.prepareStatement(
            "SELECT step, draft_value FROM conversation_states WHERE telegram_user_id = ?",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    ConversationState(ConversationStep.IDLE)
                } else {
                    ConversationState(
                        step = ConversationStep.valueOf(resultSet.getString("step")),
                        draftValue = resultSet.getLong("draft_value").takeUnless { resultSet.wasNull() },
                    )
                }
            }
        }
    }

    fun clearState(userId: Long) = saveState(userId, ConversationState(ConversationStep.IDLE))

    fun createReading(reading: MeterReading): MeterReading = inTransaction { connection ->
        connection.prepareStatement(
            """
            INSERT INTO readings(
                telegram_user_id, reading_date, t1_hundredths, t2_hundredths,
                tariff_t1_cents, tariff_t2_cents, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, reading.telegramUserId)
            statement.setString(2, reading.date.toString())
            statement.setLong(3, reading.t1Hundredths)
            statement.setLong(4, reading.t2Hundredths)
            statement.setLong(5, reading.tariffs.t1Cents)
            statement.setLong(6, reading.tariffs.t2Cents)
            statement.setLong(7, now())
            statement.setLong(8, now())
            statement.executeUpdate()
        }
        val id = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { resultSet ->
                resultSet.next()
                resultSet.getLong(1)
            }
        }
        reading.copy(id = id)
    }

    fun findLatestReading(userId: Long): LatestReading? = connect().use { connection ->
        findLatestReading(connection, userId)
    }

    fun updateLatestReading(
        userId: Long,
        synchronizeTariffs: Boolean,
        change: (MeterReading) -> MeterReading,
    ): LatestReading? = inTransaction { connection ->
        val latest = findLatestReading(connection, userId) ?: return@inTransaction null
        val changed = change(latest.current)
        require(changed.telegramUserId == userId) { "Нельзя изменить показание другого пользователя." }
        require(changed.id == latest.current.id) { "Можно изменить только последнее показание." }
        connection.prepareStatement(
            """
            UPDATE readings SET
                reading_date = ?, t1_hundredths = ?, t2_hundredths = ?,
                tariff_t1_cents = ?, tariff_t2_cents = ?, updated_at = ?
            WHERE id = ? AND telegram_user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, changed.date.toString())
            statement.setLong(2, changed.t1Hundredths)
            statement.setLong(3, changed.t2Hundredths)
            statement.setLong(4, changed.tariffs.t1Cents)
            statement.setLong(5, changed.tariffs.t2Cents)
            statement.setLong(6, now())
            statement.setLong(7, changed.id)
            statement.setLong(8, userId)
            check(statement.executeUpdate() == 1) { "Последнее показание было изменено одновременно с вашим запросом." }
        }
        if (synchronizeTariffs) {
            saveActiveTariffs(connection, userId, changed.tariffs)
        }
        LatestReading(changed, latest.previous)
    }

    fun getLastProcessedUpdateId(): Long? = connect().use { connection ->
        connection.prepareStatement("SELECT state_value FROM bot_state WHERE state_key = 'last_update_id'").use { statement ->
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getLong(1) else null
            }
        }
    }

    fun saveLastProcessedUpdateId(updateId: Long) {
        connect().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO bot_state(state_key, state_value) VALUES ('last_update_id', ?)
                ON CONFLICT(state_key) DO UPDATE SET state_value = excluded.state_value
                WHERE bot_state.state_value < excluded.state_value
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, updateId)
                statement.executeUpdate()
            }
        }
    }

    private fun findLatestReading(connection: Connection, userId: Long): LatestReading? {
        connection.prepareStatement(
            """
            SELECT id, telegram_user_id, reading_date, t1_hundredths, t2_hundredths,
                   tariff_t1_cents, tariff_t2_cents
            FROM readings WHERE telegram_user_id = ? ORDER BY id DESC LIMIT 2
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) return null
                val current = resultSet.toReading()
                val previous = if (resultSet.next()) resultSet.toReading() else null
                return LatestReading(current, previous)
            }
        }
    }

    private fun saveActiveTariffs(connection: Connection, userId: Long, tariffs: Tariffs) {
        connection.prepareStatement(
            """
            INSERT INTO active_tariffs(telegram_user_id, t1_cents, t2_cents, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(telegram_user_id) DO UPDATE SET
                t1_cents = excluded.t1_cents,
                t2_cents = excluded.t2_cents,
                updated_at = excluded.updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, tariffs.t1Cents)
            statement.setLong(3, tariffs.t2Cents)
            statement.setLong(4, now())
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toReading(): MeterReading = MeterReading(
        id = getLong("id"),
        telegramUserId = getLong("telegram_user_id"),
        date = LocalDate.parse(getString("reading_date")),
        t1Hundredths = getLong("t1_hundredths"),
        t2Hundredths = getLong("t2_hundredths"),
        tariffs = Tariffs(getLong("tariff_t1_cents"), getLong("tariff_t2_cents")),
    )

    private fun connect(): Connection {
        val properties = Properties().apply { setProperty("foreign_keys", "true") }
        return java.sql.DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}", properties)
    }

    private fun <T> inTransaction(block: (Connection) -> T): T = connect().use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    private fun now(): Long = clock.instant().toEpochMilli()
}

