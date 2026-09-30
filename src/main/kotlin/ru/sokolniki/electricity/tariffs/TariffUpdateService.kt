package ru.sokolniki.electricity.tariffs

import ru.sokolniki.electricity.domain.Tariffs
import ru.sokolniki.electricity.persistence.JdbcRepository
import ru.sokolniki.electricity.persistence.UserTariffTarget
import java.time.Clock
import java.time.LocalDate

/**
 * Хранит последнюю официальную рекомендацию по тарифам и находит пользователей для уведомления.
 *
 * Самостоятельно тариф пользователя не изменяет: рекомендация применяется только по явному действию пользователя.
 */
class TariffUpdateService(
    private val repository: JdbcRepository,
    private val provider: RemoteTariffServiceClient?,
    private val clock: Clock,
) {
    /** Получает текущие официальные тарифы и возвращает пользователей, ещё не получивших уведомление. */
    fun refresh(): List<TariffAlert> {
        val official = requireNotNull(provider) {
            "Не настроен адрес отдельного сервиса тарифов."
        }.fetchFor(LocalDate.now(clock))
        val previous = repository.findOfficialTariffs()
        val officialChanged = previous?.tariffs != official.tariffs
        if (officialChanged) {
            repository.clearTariffAlerts()
        }
        repository.saveOfficialTariffs(official.tariffs, official.retrievedAt)
        val savedOfficial = requireNotNull(latest())
        if (previous != null && !officialChanged) return emptyList()

        return repository.findUsersWithActiveTariffs().mapNotNull { target ->
            if (repository.wasTariffAlertSent(target.profile.telegramUserId, savedOfficial.tariffs)) return@mapNotNull null
            TariffAlert(target, savedOfficial, previous == null, officialChanged)
        }
    }

    /** Возвращает последнюю полученную официальную рекомендацию без сетевого запроса. */
    fun latest(): OfficialTariffs? = repository.findOfficialTariffs()?.let { snapshot ->
        OfficialTariffs(snapshot.tariffs, OFFICIAL_CALCULATOR_URL, snapshot.retrievedAt)
    }

    /** Сохраняет последнюю рекомендацию как активный тариф пользователя только для будущих показаний. */
    fun applyLatestRecommendation(userId: Long): Tariffs {
        val tariffs = requireNotNull(repository.findOfficialTariffs()) {
            "Рекомендуемые тарифы ещё не загружены. Повторите попытку через минуту."
        }.tariffs
        repository.saveActiveTariffs(userId, tariffs)
        repository.markTariffAlertSent(userId, tariffs)
        return tariffs
    }

    /** Отмечает уведомление доставленным, чтобы не повторять его ежедневно для того же тарифа. */
    fun markAlertSent(userId: Long, tariffs: Tariffs) = repository.markTariffAlertSent(userId, tariffs)

    companion object {
        const val OFFICIAL_CALCULATOR_URL = "https://www.mosenergosbyt.ru/individuals/tariffs-n-payments/calc/"
    }
}

/** Содержит получателя и изменение либо расхождение официальных тарифов, о котором нужно сообщить. */
data class TariffAlert(
    val target: UserTariffTarget,
    val official: OfficialTariffs,
    val firstSuccessfulLookup: Boolean,
    val officialChanged: Boolean,
)
