package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.core.main.domain.usecase.*
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.arch.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * MVI store for the main translation screen.
 *
 * ### Responsibilities
 * - Owning [MainState] and exposing it as a [StateFlow]
 * - Routing [MainIntent]s to the appropriate use cases
 * - Emitting one-shot [MainEvent]s for the UI (e.g. status bar messages)
 * - Setting up background observers for instant translation and spell checking
 *
 * ### Dispatch model
 * Simple synchronous state mutations (text input, language selection, etc.) are
 * applied directly via [MutableStateFlow.update] without launching a coroutine.
 * Only intents that require suspend operations launch on [scope].
 * This prevents out-of-order state updates from rapid synchronous dispatches.
 *
 * ### OCR flow
 * [MainIntent.OcrAndTranslateImage] extracts text via [OcrAndTranslateUseCase],
 * writes the result into [MainState.inputText], then triggers translation — the
 * same path as if the user had typed the text manually.
 */
class MainStore(
    private val scope: CoroutineScope,
    private val settingsState: StateFlow<Configuration>,
    private val historyRepository: HistoryRepository,
    private val checkForUpdatesUseCase: CheckForUpdatesUseCase,
    private val handleTextToSpeechUseCase: HandleTextToSpeechUseCase,
    private val performSpellCheckUseCase: PerformSpellCheckUseCase,
    private val selectActiveServiceUseCase: SelectActiveServiceUseCase,
    private val translateTextUseCase: TranslateTextUseCase,
    private val swapLanguagesUseCase: SwapLanguagesUseCase,
    private val ocrAndTranslateUseCase: OcrAndTranslateUseCase
) : Store<MainState, MainIntent, MainEvent> {

    private val _state = MutableStateFlow(MainState())
    override val state: StateFlow<MainState> = _state.asStateFlow()

    private val _eventChannel = Channel<MainEvent>(Channel.BUFFERED)
    override val events: Flow<MainEvent> = _eventChannel.receiveAsFlow()

    init {
        loadInitialHistory()
        observeAvailableServices()
        observeInstantTranslation()
        observeSpellChecking()
        checkForUpdates()
    }

    // -------------------------------------------------------------------------
    // Init observers
    // -------------------------------------------------------------------------

    private fun loadInitialHistory() {
        scope.launch {
            val history = historyRepository.loadHistory()
            _state.update { it.copy(history = history, historyIndex = history.size) }
        }
    }

    private fun observeAvailableServices() {
        scope.launch {
            selectActiveServiceUseCase.observe().collect { selection ->
                _state.update {
                    it.copy(
                        availableServices = selection.availableServices,
                        availableLanguages = selection.availableLanguages.sortedWith(
                            compareBy<LanguageCode> { lc -> lc.tag != "auto" }
                                .thenBy { lc -> lc.getDisplayName() }
                        )
                    )
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeInstantTranslation() {
        scope.launch {
            state.map { it.inputText }
                .debounce(AppConstants.INSTANT_TRANSLATION_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { text ->
                    if (settingsState.value.isInstantTranslationEnabled && text.isNotBlank()) {
                        translateText()
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
            ) { text, isEnabled -> text to isEnabled }
                .debounce(AppConstants.SPELL_CHECK_DEBOUNCE_MS)
                .collect { (text, isEnabled) -> handleSpellCheck(text, isEnabled) }
        }
    }

    private fun checkForUpdates() {
        scope.launch {
            checkForUpdatesUseCase(onStatusUpdate = ::updateStatusBar)
        }
    }

    // -------------------------------------------------------------------------
    // Intent dispatch
    // -------------------------------------------------------------------------

    override fun dispatch(intent: MainIntent) {
        when (intent) {

            // ---- Synchronous state mutations — no coroutine needed ----

            is MainIntent.UpdateInputText -> {
                // Remove line breaks if enabled — replaces \n with space so
                // PDF-copied text translates as complete sentences (Mohamed's request)
                val cleaned = if (settingsState.value.isRemoveLineBreaksEnabled)
                    intent.text.replace("\n", " ").replace("\r", "").replace("  ", " ").trim()
                else intent.text
                _state.update { it.copy(inputText = cleaned, detectedSourceLanguage = null) }
            }

            is MainIntent.SelectSourceLanguage ->
                _state.update { it.copy(sourceLanguage = intent.language, detectedSourceLanguage = null) }

            is MainIntent.SelectTargetLanguage ->
                _state.update { it.copy(targetLanguage = intent.language) }

            is MainIntent.ApplyCorrection ->
                _state.update { it.copy(inputText = it.inputText.replaceFirst(intent.original, intent.suggestion)) }

            MainIntent.HideQuickTranslate ->
                _state.update { it.copy(isQuickTranslateDialogVisible = false) }

            MainIntent.ToggleQuickTranslateDialogPin ->
                // Use `it` from the update lambda — not _state.value — to avoid
                // a data race between the value read and the update being applied.
                _state.update { it.copy(isQuickTranslateDialogPinned = !it.isQuickTranslateDialogPinned) }

            MainIntent.UndoTranslation -> handleUndo()
            MainIntent.RedoTranslation -> handleRedo()
            MainIntent.CycleTargetLanguage -> handleCycleTargetLanguage()

            // ---- Async operations — launched on scope ----

            MainIntent.SwapLanguages -> scope.launch { swapLanguages() }
            MainIntent.CheckForUpdates -> checkForUpdates()
            MainIntent.PerformSpellCheck -> scope.launch {
                handleSpellCheck(_state.value.inputText, isEnabled = true)
            }

            is MainIntent.Translate -> scope.launch { translateText(intent.text) }

            is MainIntent.ReplaceWithTranslation -> scope.launch {
                handleReplaceWithTranslation(intent.selectedText)
            }

            is MainIntent.ListenToText -> scope.launch {
                handleListen(intent.textSource, intent.text)
            }

            is MainIntent.OcrAndTranslateImage -> scope.launch {
                handleOcrAndTranslate(intent)
            }

            is MainIntent.ShowQuickTranslate -> scope.launch {
                handleShowQuickTranslate(intent)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Async handlers
    // -------------------------------------------------------------------------

    private suspend fun handleOcrAndTranslate(intent: MainIntent.OcrAndTranslateImage) {
        val extractedText = ocrAndTranslateUseCase(
            image = intent.image,
            currentState = _state.value,
            onStatusUpdate = ::updateStatusBar
        )

        if (extractedText.isBlank()) return

        // Write extracted text into input then translate — same path as manual typing.
        _state.update { it.copy(inputText = extractedText) }
        translateText()
    }

    private suspend fun handleShowQuickTranslate(intent: MainIntent.ShowQuickTranslate) {
        if (intent.selectedText.isBlank()) return

        val current = _state.value
        val isPinnedAndVisible = current.isQuickTranslateDialogVisible && current.isQuickTranslateDialogPinned

        if (isPinnedAndVisible) {
            // Popup is already open and pinned — just update the text and retranslate.
            _state.update { it.copy(inputText = intent.selectedText) }
        } else {
            // Open a fresh popup — not pinned.
            _state.update {
                it.copy(
                    inputText = intent.selectedText,
                    isQuickTranslateDialogPinned = false,
                    isQuickTranslateDialogVisible = true
                )
            }
        }

        // Let TranslateTextUseCase own isLoading — it sets it at the start of the job.
        translateText()
    }

    private suspend fun translateText(textOverride: String? = null) {
        translateTextUseCase(
            getState    = { _state.value },
            updateState = { transform -> _state.update(transform) },
            onStatusUpdate = ::updateStatusBar,
            textOverride = textOverride
        )
    }

    private suspend fun handleListen(textSource: TextSource, textOverride: String?) {
        handleTextToSpeechUseCase(
            currentState   = _state.value,
            textSource     = textSource,
            textOverride   = textOverride,
            onStatusUpdate = ::updateStatusBar
        )
    }

    private suspend fun handleSpellCheck(text: String, isEnabled: Boolean) {
        val corrections = if (isEnabled && text.isNotBlank()) {
            performSpellCheckUseCase(
                currentState   = _state.value,
                text           = text,
                onStatusUpdate = ::updateStatusBar
            )
        } else {
            emptyList()
        }
        _state.update { it.copy(spellCheckCorrections = corrections) }
    }

    // -------------------------------------------------------------------------
    // Synchronous handlers
    // -------------------------------------------------------------------------

    private fun swapLanguages() {
        swapLanguagesUseCase(
            currentState     = _state.value,
            onStateUpdate    = { newState -> _state.value = newState },
            onTranslateNeeded = { dispatch(MainIntent.Translate()) }
        )
    }

    private fun handleUndo() {
        val current = _state.value
        if (!current.canUndo) return

        val newIndex = current.historyIndex - 1
        val snapshot = current.history[newIndex]

        _state.update {
            it.copy(
                inputText              = snapshot.inputText,
                translatedText         = snapshot.translatedText,
                sourceLanguage         = LanguageCode(snapshot.sourceLanguage),
                targetLanguage         = LanguageCode(snapshot.targetLanguage),
                historyIndex           = newIndex,
                isLoading              = false,
                extraOutputText        = "",
                detectedSourceLanguage = null,
                spellCheckCorrections  = emptyList()
            )
        }
    }

    /**
     * Moves forward in history. If [historyIndex] is already at the end of [MainState.history],
     * moving "forward" clears the screen — the implicit state beyond the last snapshot is blank.
     * This allows the user to return to an empty input after browsing history.
     */
    private fun handleRedo() {
        val current = _state.value
        if (!current.canRedo) return

        val newIndex = current.historyIndex + 1

        if (newIndex == current.history.size) {
            // Past the last snapshot — restore blank state.
            _state.update {
                it.copy(
                    inputText              = "",
                    translatedText         = "",
                    extraOutputText        = "",
                    detectedSourceLanguage = null,
                    spellCheckCorrections  = emptyList(),
                    historyIndex           = newIndex
                )
            }
        } else {
            val snapshot = current.history[newIndex]
            _state.update {
                it.copy(
                    inputText              = snapshot.inputText,
                    translatedText         = snapshot.translatedText,
                    sourceLanguage         = LanguageCode(snapshot.sourceLanguage),
                    targetLanguage         = LanguageCode(snapshot.targetLanguage),
                    historyIndex           = newIndex,
                    isLoading              = false,
                    extraOutputText        = "",
                    detectedSourceLanguage = null,
                    spellCheckCorrections  = emptyList()
                )
            }
        }
    }

    private suspend fun handleReplaceWithTranslation(selectedText: String) {
        if (selectedText.isBlank()) return
        // isReplacingSelection=true tells the LoadingIndicator observer to show
        // even when the main window is visible. focusableWindowState=false on
        // LoadingIndicator means it never steals focus from the source app.
        _state.update { it.copy(inputText = selectedText, isReplacingSelection = true) }
        translateTextUseCase(
            getState       = { _state.value },
            updateState    = { transform -> _state.update(transform) },
            onStatusUpdate = ::updateStatusBar,
            textOverride   = selectedText
        )
        val result = _state.value.translatedText
        _state.update { it.copy(isReplacingSelection = false) }
        if (result.isNotBlank()) {
            _eventChannel.send(MainEvent.PasteTranslation(result))
        }
    }

    private fun handleCycleTargetLanguage() {
        val languages = _state.value.availableLanguages.filter { it != LanguageCode.AUTO }
        if (languages.isEmpty()) return
        val current = _state.value.targetLanguage
        val currentIdx = languages.indexOf(current)
        val nextIdx = (currentIdx + 1) % languages.size
        _state.update { it.copy(targetLanguage = languages[nextIdx]) }
    }

    suspend fun onShutdown() {
        if (settingsState.value.clearHistoryOnExit) {
            historyRepository.clearHistory()
        }
        handleTextToSpeechUseCase.shutdown()
    }

    private suspend fun updateStatusBar(
        text: String,
        type: NotificationType,
        isTemporary: Boolean
    ) {
        _eventChannel.send(MainEvent.UpdateStatusBar(text, type, isTemporary))
    }
}