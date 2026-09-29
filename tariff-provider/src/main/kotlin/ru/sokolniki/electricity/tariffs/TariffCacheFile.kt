package ru.sokolniki.electricity.tariffs

import ru.sokolniki.electricity.domain.Tariffs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.util.Properties

/** Хранит единственную актуальную пару тарифов для одной календарной даты в постоянном файле. */
class TariffCacheFile(private val path: Path) {
    /** Загружает сохранённый кэш либо возвращает null, если файла ещё нет или он повреждён. */
    fun load(): CachedTariffs? = runCatching {
        if (!Files.exists(path)) return null
        val values = Properties().apply { Files.newInputStream(path).use(::load) }
        CachedTariffs(
            date = LocalDate.parse(requireNotNull(values.getProperty("date"))),
            tariffs = Tariffs(
                requireNotNull(values.getProperty("t1Cents")).toLong(),
                requireNotNull(values.getProperty("t2Cents")).toLong(),
            ),
            sourceUrl = requireNotNull(values.getProperty("sourceUrl")),
            retrievedAt = Instant.parse(requireNotNull(values.getProperty("retrievedAt"))),
        )
    }.getOrNull()

    /** Атомарно заменяет файл одной новой записью, удаляя кэш за предыдущую дату. */
    fun save(date: LocalDate, tariffs: OfficialTariffs) {
        val directory = path.parent ?: Path.of(".")
        Files.createDirectories(directory)
        val values = Properties().apply {
            setProperty("date", date.toString())
            setProperty("t1Cents", tariffs.tariffs.t1Cents.toString())
            setProperty("t2Cents", tariffs.tariffs.t2Cents.toString())
            setProperty("sourceUrl", tariffs.sourceUrl)
            setProperty("retrievedAt", tariffs.retrievedAt.toString())
        }
        val temporary = Files.createTempFile(directory, path.fileName.toString(), ".tmp")
        try {
            Files.newOutputStream(temporary).use { values.store(it, "Кэш официальных тарифов") }
            runCatching {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

/** Представляет единственную запись постоянного кэша сервиса тарифов. */
data class CachedTariffs(
    val date: LocalDate,
    val tariffs: Tariffs,
    val sourceUrl: String,
    val retrievedAt: Instant,
) {
    fun toOfficialTariffs(): OfficialTariffs = OfficialTariffs(tariffs, sourceUrl, retrievedAt)
}
