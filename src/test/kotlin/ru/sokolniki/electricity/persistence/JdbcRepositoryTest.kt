package ru.sokolniki.electricity.persistence

import ru.sokolniki.electricity.application.ReadingService
import ru.sokolniki.electricity.domain.EditableField
import ru.sokolniki.electricity.domain.ElectricityCalculator
import ru.sokolniki.electricity.domain.HistoryEntry
import ru.sokolniki.electricity.domain.Tariffs
import ru.sokolniki.electricity.domain.UserProfile
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JdbcRepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `keeps readings and tariffs isolated by Telegram user`() {
        val database = Files.createTempFile("electricity-test", ".db")
        try {
            val repository = JdbcRepository(database, clock)
            repository.migrate()
            repository.saveProfile(UserProfile(1, 101, "1"))
            repository.saveProfile(UserProfile(2, 202, "2"))
            repository.saveActiveTariffs(1, Tariffs(733, 332))
            repository.saveActiveTariffs(2, Tariffs(900, 400))
            ReadingService(repository, ElectricityCalculator(), clockAt("2026-09-27")).addReading(1, 10_000, 20_000)
            val service = ReadingService(repository, ElectricityCalculator(), clock)
            service.addReading(2, 70_000, 80_000)
            val secondForFirstUser = service.addReading(1, 10_100, 20_200)

            assertEquals("1.00", secondForFirstUser.consumption.t1Kwh.toPlainString())
            assertEquals("2.00", secondForFirstUser.consumption.t2Kwh.toPlainString())
            assertEquals(14, secondForFirstUser.paymentRubles)
            assertEquals(733, repository.findLatestReading(1)?.current?.tariffs?.t1Cents)
            assertEquals(900, repository.findLatestReading(2)?.current?.tariffs?.t1Cents)
            assertNull(repository.findLatestReading(3))
        } finally {
            Files.deleteIfExists(database)
        }
    }

    @Test
    fun `rejects invalid edit without changing saved reading`() {
        val database = Files.createTempFile("electricity-edit-test", ".db")
        try {
            val repository = JdbcRepository(database, clock)
            repository.migrate()
            repository.saveProfile(UserProfile(1, 101, "1"))
            repository.saveActiveTariffs(1, Tariffs(733, 332))
            ReadingService(repository, ElectricityCalculator(), clockAt("2026-09-27")).addReading(1, 10_000, 20_000)
            val service = ReadingService(repository, ElectricityCalculator(), clock)
            service.addReading(1, 12_000, 23_000)

            assertFailsWith<IllegalArgumentException> {
                service.updateLatest(1, EditableField.READING_T1, 9_999)
            }

            assertEquals(12_000, repository.findLatestReading(1)?.current?.t1Hundredths)
        } finally {
            Files.deleteIfExists(database)
        }
    }

    @Test
    fun `replaces only requesting users history and synchronizes latest tariffs`() {
        val database = Files.createTempFile("electricity-history-test", ".db")
        try {
            val repository = JdbcRepository(database, clock)
            repository.migrate()
            repository.saveProfile(UserProfile(1, 101, "1"))
            repository.saveProfile(UserProfile(2, 202, "2"))
            repository.saveActiveTariffs(1, Tariffs(733, 332))
            repository.saveActiveTariffs(2, Tariffs(900, 400))
            ReadingService(repository, ElectricityCalculator(), clockAt("2026-09-27")).addReading(1, 10_000, 20_000)
            val service = ReadingService(repository, ElectricityCalculator(), clock)
            service.addReading(1, 10_200, 20_300)
            service.addReading(2, 70_000, 80_000)

            assertEquals(25, service.totalPaymentRubles(1))

            val result = service.replaceHistory(
                1,
                listOf(
                    HistoryEntry(LocalDate.parse("2026-07-01"), 11_000, 21_000, Tariffs(700, 300)),
                    HistoryEntry(LocalDate.parse("2026-08-01"), 11_100, 21_300, Tariffs(710, 310)),
                ),
            )

            assertEquals(16, service.importedHistoryTotalPaymentRubles(
                listOf(
                    HistoryEntry(LocalDate.parse("2026-07-01"), 11_000, 21_000, Tariffs(700, 300)),
                    HistoryEntry(LocalDate.parse("2026-08-01"), 11_100, 21_300, Tariffs(710, 310)),
                ),
            ))
            assertEquals(2, result.removedCount)
            assertEquals(2, result.importedCount)
            assertEquals(2, repository.findReadings(1).size)
            assertEquals(70_000, repository.findLatestReading(2)?.current?.t1Hundredths)
            assertEquals(710, repository.findActiveTariffs(1)?.t1Cents)
            assertEquals(900, repository.findActiveTariffs(2)?.t1Cents)
        } finally {
            Files.deleteIfExists(database)
        }
    }

    @Test
    fun `rejects a second reading for the same user and date`() {
        val database = Files.createTempFile("electricity-date-test", ".db")
        try {
            val repository = JdbcRepository(database, clock)
            repository.migrate()
            repository.saveProfile(UserProfile(1, 101, "1"))
            repository.saveActiveTariffs(1, Tariffs(733, 332))
            val service = ReadingService(repository, ElectricityCalculator(), clock)

            service.addReading(1, 10_000, 20_000)
            val error = assertFailsWith<IllegalArgumentException> {
                service.addReading(1, 10_100, 20_200)
            }

            assertEquals(
                "Показание за 28.09.2026 уже сохранено. Если нужно исправление, используйте «Изменить показание».",
                error.message,
            )
            assertEquals(1, repository.countReadings(1))
        } finally {
            Files.deleteIfExists(database)
        }
    }

    @Test
    fun `stores official tariffs and notification markers independently from user tariffs`() {
        val database = Files.createTempFile("electricity-tariff-alert-test", ".db")
        try {
            val repository = JdbcRepository(database, clock)
            repository.migrate()
            repository.saveProfile(UserProfile(1, 101, "1"))
            repository.saveProfile(UserProfile(2, 202, "2"))
            repository.saveActiveTariffs(1, Tariffs(700, 300))
            val official = Tariffs(773, 332)

            repository.saveOfficialTariffs(official)

            assertEquals(official, repository.findOfficialTariffs()?.tariffs)
            assertEquals(clock.instant(), repository.findOfficialTariffs()?.retrievedAt)
            assertEquals(2, repository.findUsersWithActiveTariffs().size)
            assertNull(repository.findUsersWithActiveTariffs().single { it.profile.telegramUserId == 2L }.tariffs)
            assertEquals(false, repository.wasTariffAlertSent(1, official))

            repository.markTariffAlertSent(1, official)
            assertEquals(true, repository.wasTariffAlertSent(1, official))
            assertEquals(Tariffs(700, 300), repository.findActiveTariffs(1))

            repository.clearTariffAlerts()
            assertEquals(false, repository.wasTariffAlertSent(1, official))
        } finally {
            Files.deleteIfExists(database)
        }
    }

    @Test
    fun `finds stale reading users once and clears reminder after a new reading`() {
        val database = Files.createTempFile("electricity-reading-reminder-test", ".db")
        try {
            val repository = JdbcRepository(database, clock)
            repository.migrate()
            repository.saveProfile(UserProfile(1, 101, "1"))
            repository.saveProfile(UserProfile(2, 202, "2"))
            repository.saveProfile(UserProfile(3, 303, "3"))
            repository.saveActiveTariffs(1, Tariffs(733, 332))
            repository.saveActiveTariffs(2, Tariffs(733, 332))
            repository.saveActiveTariffs(3, Tariffs(733, 332))
            ReadingService(repository, ElectricityCalculator(), clockAt("2026-08-20")).addReading(1, 10_000, 20_000)
            ReadingService(repository, ElectricityCalculator(), clock).addReading(3, 30_000, 40_000)

            val cutoff = LocalDate.parse("2026-09-03")
            assertEquals(
                setOf(1L, 2L),
                repository.findUsersNeedingReadingReminder(cutoff, YearMonth.of(2026, 9)).map { it.profile.telegramUserId }.toSet(),
            )

            repository.markReadingReminderSent(1, YearMonth.of(2026, 9))
            assertEquals(
                setOf(2L),
                repository.findUsersNeedingReadingReminder(cutoff, YearMonth.of(2026, 9)).map { it.profile.telegramUserId }.toSet(),
            )

            ReadingService(repository, ElectricityCalculator(), clock).addReading(1, 10_100, 20_100)
            assertEquals(
                setOf(1L, 2L, 3L),
                repository.findUsersNeedingReadingReminder(LocalDate.parse("2026-10-01"), YearMonth.of(2026, 10))
                    .map { it.profile.telegramUserId }.toSet(),
            )
        } finally {
            Files.deleteIfExists(database)
        }
    }

    private fun clockAt(date: String): Clock = Clock.fixed(
        Instant.parse("${date}T12:00:00Z"),
        ZoneOffset.UTC,
    )
}

