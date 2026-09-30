package ru.sokolniki.electricity.persistence

import ru.sokolniki.electricity.domain.UserProfile

/** Работает с профилями пользователей из таблицы [user_profiles]. */
class UserProfileRepository(database: SqliteDatabase) : BaseJdbcRepository(database) {
    /** Создаёт профиль либо обновляет участок и чат существующего пользователя. */
    fun save(profile: UserProfile) {
        val timestamp = now()
        query { connection ->
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
                statement.setLong(4, timestamp)
                statement.setLong(5, timestamp)
                statement.executeUpdate()
            }
        }
    }

    /** Синхронизирует chat ID пользователя с последним полученным обновлением Telegram. */
    fun updateKnownChat(userId: Long, chatId: Long) {
        query { connection ->
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

    /** Находит профиль пользователя по его Telegram ID. */
    fun find(userId: Long): UserProfile? = query { connection ->
        connection.prepareStatement(
            "SELECT telegram_user_id, chat_id, plot_number FROM user_profiles WHERE telegram_user_id = ?",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toProfile() else null
            }
        }
    }

    /** Возвращает общее число зарегистрированных пользователей. */
    fun count(): Int = query { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM user_profiles").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }
    }
}

/** Преобразует строку таблицы профилей в доменную модель пользователя. */
internal fun java.sql.ResultSet.toProfile(): UserProfile = UserProfile(
    telegramUserId = getLong("telegram_user_id"),
    chatId = getLong("chat_id"),
    plotNumber = getString("plot_number"),
)
