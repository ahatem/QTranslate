package com.github.ahatem.qtranslate.core.main.mvi


import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.main.domain.usecase.*
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.arch.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainStore(
    private val scope: CoroutineScope,
    // Core Dependencies
    private val settingsState: StateFlow<Configuration>,
    private val historyRepository: HistoryRepository,
    // Use Cases
    private val checkForUpdatesUseCase: CheckForUpdatesUseCase,
    private val handleTextToSpeechUseCase: HandleTextToSpeechUseCase,
    private val performSpellCheckUseCase: PerformSpellCheckUseCase,
    private val selectActiveServiceUseCase: SelectActiveServiceUseCase,
    private val translateTextUseCase: TranslateTextUseCase,
    private val swapLanguagesUseCase: SwapLanguagesUseCase,
    private val historyNavigationUseCase: HistoryNavigationUseCase,
    private val ocrAndTranslateUseCase: OcrAndTranslateUseCase
) : Store<MainState, MainIntent, MainEvent> {

    private val _state = MutableStateFlow(MainState())
    override val state: StateFlow<MainState> = _state.asStateFlow()

    private val _eventChannel = Channel<MainEvent>(Channel.BUFFERED)
    override val events: Flow<MainEvent> = _eventChannel.receiveAsFlow()

    init {
        loadInitialHistory()
        observeSettingsAndServices()
        observeInstantTranslation()
        observeSpellChecking()
        checkForUpdates()
    }

    private fun loadInitialHistory() {
        scope.launch {
            val history = historyRepository.loadHistory()
            _state.update { it.copy(history = history, historyIndex = history.lastIndex) }
        }
    }

    private fun observeSettingsAndServices() {
        scope.launch {
            selectActiveServiceUseCase.observe().collect { (services, selected, languages) ->
                _state.update {
                    it.copy(
                        availableServices = services,
                        selectedServices = selected,
                        availableLanguages = languages
                    )
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeInstantTranslation() {
        scope.launch {
            state.map { it.inputText }
                .debounce(500L)
                .distinctUntilChanged()
                .collect { text ->
                    if (settingsState.value.isInstantTranslationEnabled && text.isNotBlank()) {
                        dispatch(MainIntent.Translate())
                    }
                }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeSpellChecking() {
        scope.launch {
            combine(
                state.map { it.inputText }.distinctUntilChanged(),
                settingsState.map { it.isSpellCheckingEnabled }.distinctUntilChanged()
            ) { text, isEnabled -> Pair(text, isEnabled) }
                .debounce(750L)
                .collect { (text, isEnabled) ->
                    val corrections = if (isEnabled && text.isNotBlank()) {
                        performSpellCheckUseCase(state.value, text, onStatusUpdate = ::updateStatusBar)
                    } else {
                        emptyList()
                    }
                    _state.update { it.copy(spellCheckCorrections = corrections) }
                }
        }
    }

    private fun checkForUpdates() {
        scope.launch {
            checkForUpdatesUseCase(onStatusUpdate = ::updateStatusBar)
        }
    }

    override fun dispatch(intent: MainIntent) {
        scope.launch {
            when (intent) {
                is MainIntent.UpdateInputText -> updateInputText(intent.text)
                is MainIntent.SelectSourceLanguage -> _state.update { it.copy(sourceLanguage = intent.language) }
                is MainIntent.SelectTargetLanguage -> _state.update { it.copy(targetLanguage = intent.language) }
                is MainIntent.SelectService -> selectService(intent.type, intent.serviceId)
                is MainIntent.ApplyCorrection -> applyCorrection(intent.original, intent.suggestion)
                MainIntent.SwapLanguages -> swapLanguages()
                MainIntent.UndoTranslation -> handleUndo()
                MainIntent.RedoTranslation -> handleRedo()
                MainIntent.CheckForUpdates -> checkForUpdates()

                is MainIntent.ShowQuickTranslate -> {
                    if (intent.selectedText.isBlank()) {
                        return@launch
                    }

                    val currentState = _state.value
                    val isAlreadyPinnedAndVisible =
                        currentState.isQuickTranslateDialogVisible && currentState.isQuickTranslateDialogPinned

                    if (isAlreadyPinnedAndVisible) {
                        _state.update {
                            it.copy(
                                inputText = intent.selectedText,
                                isLoading = true
                            )
                        }
                        translateText()
                    } else {
                        _state.update {
                            it.copy(
                                inputText = intent.selectedText,
                                isQuickTranslateDialogPinned = false,
                                isLoading = true
                            )
                        }
                        translateText()
                        _state.update { it.copy(isQuickTranslateDialogVisible = true) }
                    }
                }

                MainIntent.HideQuickTranslate -> {
                    _state.update { it.copy(isQuickTranslateDialogVisible = false) }
                }

                MainIntent.ToggleQuickTranslateDialogPin -> {
                    _state.update { it.copy(isQuickTranslateDialogPinned = !_state.value.isQuickTranslateDialogPinned) }
                }

                is MainIntent.Translate -> translateText(intent.text)
                is MainIntent.ListenToText -> handleListen(intent.textSource, intent.text)
                is MainIntent.OcrAndTranslateImage -> ocrAndTranslateUseCase(
                    intent.image,
                    state.value,
                    ::updateStatusBar
                )
            }
        }
    }

    private fun updateInputText(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    private fun selectService(type: ServiceType, serviceId: String?) {
        scope.launch {
            val newSelections = _state.value.selectedServices.toMutableMap()
            newSelections[type] = serviceId
            _state.update { it.copy(selectedServices = newSelections) }

            if (type == ServiceType.TRANSLATOR) {
                val languages = selectActiveServiceUseCase.getLanguagesFor(serviceId)
                _state.update { it.copy(availableLanguages = languages) }
            }
        }
    }

    private fun applyCorrection(original: String, suggestion: String) {
        _state.update {
            it.copy(inputText = it.inputText.replaceFirst(original, suggestion))
        }
    }

    private fun swapLanguages() {
        swapLanguagesUseCase(
            currentState = _state.value,
            onStateUpdate = { newState -> _state.value = newState },
            onTranslateNeeded = { dispatch(MainIntent.Translate()) }
        )
    }

    private fun handleUndo() {
        val newState = historyNavigationUseCase.undo(_state.value)
        if (newState != null) {
            _state.value = newState
        }
    }

    private fun handleRedo() {
        val newState = historyNavigationUseCase.redo(_state.value)
        if (newState != null) {
            _state.value = newState
        }
    }

    private suspend fun translateText(textOverride: String? = null) {
        translateTextUseCase(
            getState = { _state.value },
            updateState = { newState -> _state.update(newState) },
            onStatusUpdate = ::updateStatusBar,
            textOverride = textOverride
        )
    }

    private suspend fun handleListen(textSource: TextSource, textOverride: String?) {
        handleTextToSpeechUseCase(
            currentState = _state.value,
            textSource = textSource,
            textOverride = textOverride,
            onStatusUpdate = ::updateStatusBar
        )
    }

    suspend fun onShutdown() {
        if (settingsState.value.clearHistoryOnExit) {
            historyRepository.clearHistory()
        }
        handleTextToSpeechUseCase.shutdown()
    }


    private suspend fun updateStatusBar(text: String, type: NotificationType) {
        _eventChannel.send(MainEvent.UpdateStatusBar(text, type))
    }

}