package ru.sokolniki.electricity.tariffs

import ru.sokolniki.electricity.domain.Tariffs
import ru.sokolniki.electricity.persistence.JdbcRepository
import ru.sokolniki.electricity.persistence.UserTariffTarget

/**
 * Stores the latest official tariff recommendation and finds users who need to receive an alert.
 *
 * It never changes a user's tariff; applying a recommendation is an explicit user action.
 */
class TariffUpdateService(
    private val repository: JdbcRepository,
    private val provider: MosenergosbytTariffProvider,
) {
    /** Retrieves current official tariffs and returns users that have not yet received this alert. */
    fun refresh(): List<TariffAlert> {
        val official = provider.fetch()
        val previous = repository.findOfficialTariffs()
        val officialChanged = previous != official.tariffs
        if (officialChanged) {
            repository.saveOfficialTariffs(official.tariffs)
            repository.clearTariffAlerts()
        }

        return repository.findUsersWithActiveTariffs().mapNotNull { target ->
            if (repository.wasTariffAlertSent(target.profile.telegramUserId, official.tariffs)) return@mapNotNull null
            TariffAlert(target, official, previous == null, officialChanged)
        }
    }

    /** Returns the latest retrieved official recommendation without making a network request. */
    fun latest(): OfficialTariffs? = repository.findOfficialTariffs()?.let { tariffs ->
        OfficialTariffs(tariffs, OFFICIAL_CALCULATOR_URL)
    }

    /** Saves the latest recommendation as the caller's active tariff for future readings only. */
    fun applyLatestRecommendation(userId: Long): Tariffs {
        val tariffs = requireNotNull(repository.findOfficialTariffs()) {
            "Рекомендуемые тарифы ещё не загружены. Повторите попытку через минуту."
        }
        repository.saveActiveTariffs(userId, tariffs)
        repository.markTariffAlertSent(userId, tariffs)
        return tariffs
    }

    /** Marks an alert as delivered to prevent repeated daily notifications for the same tariff. */
    fun markAlertSent(userId: Long, tariffs: Tariffs) = repository.markTariffAlertSent(userId, tariffs)

    companion object {
        const val OFFICIAL_CALCULATOR_URL = "https://www.mosenergosbyt.ru/individuals/tariffs-n-payments/calc/"
    }
}

/** Contains one recipient and the official tariff change or mismatch that must be communicated. */
data class TariffAlert(
    val target: UserTariffTarget,
    val official: OfficialTariffs,
    val firstSuccessfulLookup: Boolean,
    val officialChanged: Boolean,
)
