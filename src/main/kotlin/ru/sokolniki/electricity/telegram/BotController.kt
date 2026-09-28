package ru.sokolniki.electricity.telegram

import ru.sokolniki.electricity.application.ReadingService
import ru.sokolniki.electricity.domain.ConversationState
import ru.sokolniki.electricity.domain.ConversationStep
import ru.sokolniki.electricity.domain.EditableField
import ru.sokolniki.electricity.domain.InputParser
import ru.sokolniki.electricity.domain.MessageFormatter
import ru.sokolniki.electricity.domain.Tariffs
import ru.sokolniki.electricity.domain.UserProfile
import ru.sokolniki.electricity.persistence.JdbcRepository

class BotController(
    private val telegram: TelegramClient,
    private val repository: JdbcRepository,
    private val readings: ReadingService,
    private val messages: MessageFormatter,
) {
    fun handle(update: IncomingUpdate) {
        update.message?.let {
            repository.updateKnownChat(it.userId, it.chatId)
            handleMessage(it)
            return
        }
        update.callback?.let {
            repository.updateKnownChat(it.userId, it.chatId ?: return@let)
            handleCallback(it)
        }
    }

    private fun handleMessage(message: IncomingMessage) {
        val input = message.text?.trim().orEmpty()
        if (input.isEmpty()) return

        if (input == "/start" || input.startsWith("/start@") || input == ButtonText.MENU || input == "/cancel") {
            repository.clearState(message.userId)
            showMenuOrStartSetup(message.userId, message.chatId)
            return
        }

        when (input) {
            ButtonText.ADD_READING -> {
                startReading(message.userId, message.chatId)
                return
            }
            ButtonText.TARIFFS -> {
                startTariffChange(message.userId, message.chatId)
                return
            }
            ButtonText.EDIT_LAST -> {
                startLastReadingEdit(message.userId, message.chatId)
                return
            }
            ButtonText.LAST_READING -> {
                showLatestReading(message.userId, message.chatId)
                return
            }
            ButtonText.BANK -> {
                sendBankMessage(message.userId, message.chatId)
                return
            }
            ButtonText.CHAIRMAN -> {
                sendChairmanMessage(message.userId, message.chatId)
                return
            }
            ButtonText.PLOT -> {
                startPlotEdit(message.userId, message.chatId)
                return
            }
        }

        val state = repository.stateFor(message.userId)
        if (state.step == ConversationStep.IDLE) {
            sendWithMenu(message.chatId, "Выберите действие кнопкой ниже или отправьте /start.")
            return
        }
        processStateInput(message, state)
    }

    private fun handleCallback(callback: IncomingCallback) {
        telegram.answerCallback(callback.id)
        val chatId = callback.chatId ?: repository.findProfile(callback.userId)?.chatId ?: return
        when (callback.data) {
            "edit:cancel" -> {
                repository.clearState(callback.userId)
                sendWithMenu(chatId, "Редактирование отменено.")
            }
            "edit:reading_t1" -> beginFieldEdit(callback.userId, chatId, ConversationStep.EDIT_READING_T1, "Введите новое показание Т1.")
            "edit:reading_t2" -> beginFieldEdit(callback.userId, chatId, ConversationStep.EDIT_READING_T2, "Введите новое показание Т2.")
            "edit:tariff_t1" -> beginFieldEdit(callback.userId, chatId, ConversationStep.EDIT_TARIFF_T1, "Введите новый тариф Т1 в рублях за кВт·ч.")
            "edit:tariff_t2" -> beginFieldEdit(callback.userId, chatId, ConversationStep.EDIT_TARIFF_T2, "Введите новый тариф Т2 в рублях за кВт·ч.")
        }
    }

    private fun showMenuOrStartSetup(userId: Long, chatId: Long) {
        val profile = repository.findProfile(userId)
        if (profile == null) {
            repository.saveState(userId, ConversationState(ConversationStep.SETUP_PLOT))
            telegram.sendMessage(chatId, "Добро пожаловать. Введите номер вашего участка.", KeyboardFactory.setup())
        } else {
            sendWithMenu(chatId, "Главное меню. Участок ${profile.plotNumber}.")
        }
    }

    private fun startReading(userId: Long, chatId: Long) {
        if (!isConfigured(userId, chatId)) return
        if (repository.findActiveTariffs(userId) == null) {
            repository.saveState(userId, ConversationState(ConversationStep.TARIFF_T1))
            telegram.sendMessage(chatId, "Сначала укажите тариф Т1 в рублях за кВт·ч.", KeyboardFactory.setup())
            return
        }
        repository.saveState(userId, ConversationState(ConversationStep.READING_T1))
        telegram.sendMessage(chatId, "Введите текущее показание Т1 в кВт·ч.", KeyboardFactory.setup())
    }

    private fun startTariffChange(userId: Long, chatId: Long) {
        if (!isConfigured(userId, chatId)) return
        repository.saveState(userId, ConversationState(ConversationStep.TARIFF_T1))
        telegram.sendMessage(chatId, "Введите тариф Т1 в рублях за кВт·ч.", KeyboardFactory.setup())
    }

    private fun startPlotEdit(userId: Long, chatId: Long) {
        if (!isConfigured(userId, chatId)) return
        repository.saveState(userId, ConversationState(ConversationStep.EDIT_PLOT))
        telegram.sendMessage(chatId, "Введите новый номер участка.", KeyboardFactory.setup())
    }

    private fun startLastReadingEdit(userId: Long, chatId: Long) {
        if (!isConfigured(userId, chatId)) return
        val calculation = readings.latestCalculation(userId)
        if (calculation == null) {
            sendWithMenu(chatId, "Пока нет показаний для редактирования.")
            return
        }
        repository.clearState(userId)
        telegram.sendMessage(
            chatId,
            "Выберите, что изменить в последнем показании.\n\n${messages.readingSummary(calculation)}",
            KeyboardFactory.editLast(),
        )
    }

    private fun beginFieldEdit(userId: Long, chatId: Long, step: ConversationStep, prompt: String) {
        if (readings.latestCalculation(userId) == null) {
            sendWithMenu(chatId, "Пока нет показаний для редактирования.")
            return
        }
        repository.saveState(userId, ConversationState(step))
        telegram.sendMessage(chatId, prompt, KeyboardFactory.setup())
    }

    private fun showLatestReading(userId: Long, chatId: Long) {
        if (!isConfigured(userId, chatId)) return
        val calculation = readings.latestCalculation(userId)
        if (calculation == null) {
            sendWithMenu(chatId, "Пока нет показаний. Нажмите «${ButtonText.ADD_READING}».")
        } else {
            sendWithMenu(chatId, messages.readingSummary(calculation))
        }
    }

    private fun sendBankMessage(userId: Long, chatId: Long) {
        val profile = repository.findProfile(userId) ?: run {
            showMenuOrStartSetup(userId, chatId)
            return
        }
        val calculation = readings.latestCalculation(userId) ?: run {
            sendWithMenu(chatId, "Пока нет показаний, из которых можно составить сообщение для банка.")
            return
        }
        val text = messages.bankMessage(profile, calculation)
        telegram.sendMessage(chatId, text, KeyboardFactory.copyText(text))
    }

    private fun sendChairmanMessage(userId: Long, chatId: Long) {
        val profile = repository.findProfile(userId) ?: run {
            showMenuOrStartSetup(userId, chatId)
            return
        }
        val calculation = readings.latestCalculation(userId) ?: run {
            sendWithMenu(chatId, "Пока нет показаний, из которых можно составить сообщение для председателя.")
            return
        }
        val text = messages.chairmanMessage(profile, calculation)
        telegram.sendMessage(chatId, text, KeyboardFactory.copyText(text))
    }

    private fun processStateInput(message: IncomingMessage, state: ConversationState) {
        try {
            when (state.step) {
                ConversationStep.SETUP_PLOT -> configurePlot(message, startsSetup = true)
                ConversationStep.EDIT_PLOT -> configurePlot(message, startsSetup = false)
                ConversationStep.SETUP_TARIFF_T1 -> saveTariffT1(message, state, setup = true)
                ConversationStep.SETUP_TARIFF_T2 -> saveTariffT2(message, state, setup = true)
                ConversationStep.TARIFF_T1 -> saveTariffT1(message, state, setup = false)
                ConversationStep.TARIFF_T2 -> saveTariffT2(message, state, setup = false)
                ConversationStep.READING_T1 -> saveReadingT1(message)
                ConversationStep.READING_T2 -> saveReadingT2(message, state)
                ConversationStep.EDIT_READING_T1,
                ConversationStep.EDIT_READING_T2,
                ConversationStep.EDIT_TARIFF_T1,
                ConversationStep.EDIT_TARIFF_T2,
                -> editLatest(message, state.step)
                ConversationStep.IDLE -> Unit
            }
        } catch (error: IllegalArgumentException) {
            telegram.sendMessage(message.chatId, "${error.message ?: "Некорректное значение."}\nПовторите ввод или нажмите «${ButtonText.MENU}».", KeyboardFactory.setup())
        }
    }

    private fun configurePlot(message: IncomingMessage, startsSetup: Boolean) {
        val profile = UserProfile(message.userId, message.chatId, InputParser.plotNumber(message.text.orEmpty()))
        repository.saveProfile(profile)
        if (startsSetup) {
            repository.saveState(message.userId, ConversationState(ConversationStep.SETUP_TARIFF_T1))
            telegram.sendMessage(message.chatId, "Введите тариф Т1 в рублях за кВт·ч.", KeyboardFactory.setup())
        } else {
            repository.clearState(message.userId)
            sendWithMenu(message.chatId, "Номер участка сохранён: ${profile.plotNumber}.")
        }
    }

    private fun saveTariffT1(message: IncomingMessage, state: ConversationState, setup: Boolean) {
        val tariffT1 = InputParser.tariffCents(message.text.orEmpty())
        val nextStep = if (setup) ConversationStep.SETUP_TARIFF_T2 else ConversationStep.TARIFF_T2
        repository.saveState(message.userId, ConversationState(nextStep, tariffT1))
        telegram.sendMessage(message.chatId, "Введите тариф Т2 в рублях за кВт·ч.", KeyboardFactory.setup())
    }

    private fun saveTariffT2(message: IncomingMessage, state: ConversationState, setup: Boolean) {
        val tariffT1 = requireNotNull(state.draftValue) { "Не найден временно сохранённый тариф Т1." }
        val tariffs = Tariffs(tariffT1, InputParser.tariffCents(message.text.orEmpty()))
        repository.saveActiveTariffs(message.userId, tariffs)
        repository.clearState(message.userId)
        val prefix = if (setup) "Настройка завершена." else "Тарифы сохранены."
        sendWithMenu(
            message.chatId,
            "$prefix Т1: ${tariffs.t1Rubles.toPlainString().replace('.', ',')} ₽, Т2: ${tariffs.t2Rubles.toPlainString().replace('.', ',')} ₽.",
        )
    }

    private fun saveReadingT1(message: IncomingMessage) {
        val t1 = InputParser.readingHundredths(message.text.orEmpty())
        repository.saveState(message.userId, ConversationState(ConversationStep.READING_T2, t1))
        telegram.sendMessage(message.chatId, "Введите текущее показание Т2 в кВт·ч.", KeyboardFactory.setup())
    }

    private fun saveReadingT2(message: IncomingMessage, state: ConversationState) {
        val t1 = requireNotNull(state.draftValue) { "Не найдено введённое показание Т1." }
        val calculation = readings.addReading(message.userId, t1, InputParser.readingHundredths(message.text.orEmpty()))
        repository.clearState(message.userId)
        sendWithMenu(message.chatId, "Показание сохранено.\n\n${messages.readingSummary(calculation)}")
    }

    private fun editLatest(message: IncomingMessage, step: ConversationStep) {
        val field = when (step) {
            ConversationStep.EDIT_READING_T1 -> EditableField.READING_T1
            ConversationStep.EDIT_READING_T2 -> EditableField.READING_T2
            ConversationStep.EDIT_TARIFF_T1 -> EditableField.TARIFF_T1
            ConversationStep.EDIT_TARIFF_T2 -> EditableField.TARIFF_T2
            else -> error("Шаг не является редактированием.")
        }
        val value = when (field) {
            EditableField.READING_T1, EditableField.READING_T2 -> InputParser.readingHundredths(message.text.orEmpty())
            EditableField.TARIFF_T1, EditableField.TARIFF_T2 -> InputParser.tariffCents(message.text.orEmpty())
        }
        val calculation = readings.updateLatest(message.userId, field, value)
        requireNotNull(calculation) { "Последнее показание не найдено." }
        repository.clearState(message.userId)
        sendWithMenu(message.chatId, "Последнее показание изменено.\n\n${messages.readingSummary(calculation)}")
    }

    private fun isConfigured(userId: Long, chatId: Long): Boolean {
        if (repository.findProfile(userId) != null) return true
        showMenuOrStartSetup(userId, chatId)
        return false
    }

    private fun sendWithMenu(chatId: Long, text: String) = telegram.sendMessage(chatId, text, KeyboardFactory.main())
}

