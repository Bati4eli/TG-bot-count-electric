package ru.sokolniki.electricity.persistence

import ru.sokolniki.electricity.domain.Tariffs
import java.sql.Connection
import java.time.Instant

/** Хранит пользовательские и официальные тарифы, а также отметки рассылки об их изменении. */
class TariffRepository(database: SqliteDatabase) : BaseJdbcRepository(database) {
    /** Сохраняет активную пару тарифов пользователя. */
    fun saveActive(userId: Long, tariffs: Tariffs) = query { connection -> saveActive(connection, userId, tariffs) }

    /** Сохраняет тарифы в уже открытой транзакции и сбрасывает старую отметку рассылки. */
    internal fun saveActive(connection: Connection, userId: Long, tariffs: Tariffs) {
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
        clearAlert(connection, userId)
    }

    /** Возвращает сохранённые активные тарифы пользователя. */
    fun findActive(userId: Long): Tariffs? = query { connection ->
        connection.prepareStatement("SELECT t1_cents, t2_cents FROM active_tariffs WHERE telegram_user_id = ?").use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) Tariffs(resultSet.getLong("t1_cents"), resultSet.getLong("t2_cents")) else null
            }
        }
    }

    /** Возвращает всех пользователей с их необязательными активными тарифами. */
    fun findUsersWithActiveTariffs(): List<UserTariffTarget> = query { connection ->
        connection.prepareStatement(
            """
            SELECT p.telegram_user_id, p.chat_id, p.plot_number, t.t1_cents, t.t2_cents
            FROM user_profiles p
            LEFT JOIN active_tariffs t ON t.telegram_user_id = p.telegram_user_id
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        val tariffs = resultSet.getLong("t1_cents").takeUnless { resultSet.wasNull() }?.let { t1 ->
                            Tariffs(t1, resultSet.getLong("t2_cents"))
                        }
                        add(UserTariffTarget(resultSet.toProfile(), tariffs))
                    }
                }
            }
        }
    }

    /** Возвращает последнюю успешно полученную официальную пару тарифов. */
    fun findOfficial(): OfficialTariffSnapshot? = query { connection ->
        val t1 = readBotState(connection, OFFICIAL_TARIFF_T1_KEY)
        val t2 = readBotState(connection, OFFICIAL_TARIFF_T2_KEY)
        val retrievedAt = readBotState(connection, OFFICIAL_TARIFF_RETRIEVED_AT_KEY)
        if (t1 == null || t2 == null || retrievedAt == null) null else OfficialTariffSnapshot(Tariffs(t1, t2), Instant.ofEpochMilli(retrievedAt))
    }

    /** Атомарно сохраняет официальные тарифы и время их получения. */
    fun saveOfficial(tariffs: Tariffs, retrievedAt: Instant = Instant.ofEpochMilli(now())) = transaction { connection ->
        saveBotState(connection, OFFICIAL_TARIFF_T1_KEY, tariffs.t1Cents)
        saveBotState(connection, OFFICIAL_TARIFF_T2_KEY, tariffs.t2Cents)
        saveBotState(connection, OFFICIAL_TARIFF_RETRIEVED_AT_KEY, retrievedAt.toEpochMilli())
    }

    /** Проверяет, было ли пользователю отправлено уведомление для этой пары тарифов. */
    fun wasAlertSent(userId: Long, tariffs: Tariffs): Boolean = query { connection ->
        connection.prepareStatement(
            "SELECT 1 FROM tariff_alerts WHERE telegram_user_id = ? AND tariff_t1_cents = ? AND tariff_t2_cents = ?",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, tariffs.t1Cents)
            statement.setLong(3, tariffs.t2Cents)
            statement.executeQuery().use { it.next() }
        }
    }

    /** Фиксирует успешную доставку уведомления о тарифах. */
    fun markAlertSent(userId: Long, tariffs: Tariffs) = query { connection ->
        connection.prepareStatement(
            """
            INSERT INTO tariff_alerts(telegram_user_id, tariff_t1_cents, tariff_t2_cents, sent_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(telegram_user_id) DO UPDATE SET
                tariff_t1_cents = excluded.tariff_t1_cents,
                tariff_t2_cents = excluded.tariff_t2_cents,
                sent_at = excluded.sent_at
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, tariffs.t1Cents)
            statement.setLong(3, tariffs.t2Cents)
            statement.setLong(4, now())
            statement.executeUpdate()
        }
    }

    /** Удаляет все отметки рассылки после изменения официальной пары. */
    fun clearAlerts() = query { connection -> connection.createStatement().use { it.executeUpdate("DELETE FROM tariff_alerts") } }

    /** Удаляет отметку одного пользователя в рамках текущей транзакции. */
    private fun clearAlert(connection: Connection, userId: Long) {
        connection.prepareStatement("DELETE FROM tariff_alerts WHERE telegram_user_id = ?").use { statement ->
            statement.setLong(1, userId)
            statement.executeUpdate()
        }
    }

    /** Читает числовое техническое состояние бота. */
    private fun readBotState(connection: Connection, key: String): Long? = connection.prepareStatement(
        "SELECT state_value FROM bot_state WHERE state_key = ?",
    ).use { statement ->
        statement.setString(1, key)
        statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getLong(1) else null }
    }

    /** Сохраняет числовое техническое состояние бота. */
    private fun saveBotState(connection: Connection, key: String, value: Long) {
        connection.prepareStatement(
            "INSERT INTO bot_state(state_key, state_value) VALUES (?, ?) ON CONFLICT(state_key) DO UPDATE SET state_value = excluded.state_value",
        ).use { statement ->
            statement.setString(1, key)
            statement.setLong(2, value)
            statement.executeUpdate()
        }
    }

    private companion object {
        const val OFFICIAL_TARIFF_T1_KEY = "official_tariff_t1_cents"
        const val OFFICIAL_TARIFF_T2_KEY = "official_tariff_t2_cents"
        const val OFFICIAL_TARIFF_RETRIEVED_AT_KEY = "official_tariff_retrieved_at"
    }
}

