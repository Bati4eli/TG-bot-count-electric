package ru.sokolniki.electricity.persistence

import ru.sokolniki.electricity.application.ReadingService
import ru.sokolniki.electricity.domain.EditableField
import ru.sokolniki.electricity.domain.ElectricityCalculator
import ru.sokolniki.electricity.domain.Tariffs
import ru.sokolniki.electricity.domain.UserProfile
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
            val service = ReadingService(repository, ElectricityCalculator(), clock)

            service.addReading(1, 10_000, 20_000)
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
            val service = ReadingService(repository, ElectricityCalculator(), clock)
            service.addReading(1, 10_000, 20_000)
            service.addReading(1, 12_000, 23_000)

            assertFailsWith<IllegalArgumentException> {
                service.updateLatest(1, EditableField.READING_T1, 9_999)
            }

            assertEquals(12_000, repository.findLatestReading(1)?.current?.t1Hundredths)
        } finally {
            Files.deleteIfExists(database)
        }
    }
}

