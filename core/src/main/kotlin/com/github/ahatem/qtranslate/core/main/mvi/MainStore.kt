package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.document.DocumentTranslationException
import com.github.ahatem.qtranslate.core.document.DocumentTranslationRequest
import com.github.ahatem.qtranslate.core.document.DocumentTranslationUseCase
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.core.main.domain.usecase.*
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.arch.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
    private val ocrAndTranslateUseCase: OcrAndTranslateUseCase,
    private val summarizeUseCase: SummarizeUseCase,
    private val rewriteUseCase: RewriteUseCase,
    private val lookupWordUseCase: LookupWordUseCase,
    private val searchImagesUseCase: SearchImagesUseCase,
    private val fetchInlineDefinitionUseCase: FetchInlineDefinitionUseCase,
    private val documentTranslationUseCase: DocumentTranslationUseCase
) : Store<MainState, MainIntent, MainEvent> {

    private var documentTranslationJob: Job? = null
    private var documentTranslationGeneration = 0L

    private val _state = MutableStateFlow(
        MainState(
            isDictionaryPanelVisible = settingsState.value.showDictionaryPanel,
            isQuickDictionaryPinned  = settingsState.value.isQuickDictionaryPinned,
            targetLanguage           = LanguageCode(settingsState.value.preferredTargetLanguage),
            sourceLanguage           = LanguageCode(settingsState.value.preferredSourceLanguage)
        )
    )
    override val state: StateFlow<MainState> = _state.asStateFlow()

    private val _eventChannel = Channel<MainEvent>(Channel.BUFFERED)
    override val events: Flow<MainEvent> = _eventChannel.receiveAsFlow()

    init {
        loadInitialHistory()
        observeAvailableServices()
        observeInstantTranslation()
        observeSpellChecking()
        observeTtsPlayback()
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
                _state.update { current ->
                    val sortedLanguages = selection.availableLanguages.sortedWith(
                        compareBy<LanguageCode> { lc -> lc.tag != "auto" }
                            .thenBy { lc -> lc.getDisplayName() }
                    )

                    // If the previously selected target language is not available in the new
                    // list (e.g. because pinnedLanguages hides it, or the translator doesn't
                    // support it), fall back to the saved preference then to the first available
                    // non-auto language so the user is never silently translated to the wrong language.
                    val targetLang = when {
                        current.targetLanguage in sortedLanguages -> current.targetLanguage
                        else -> {
                            val preferred = LanguageCode(settingsState.value.preferredTargetLanguage)
                            sortedLanguages.firstOrNull { it == preferred }
                                ?: sortedLanguages.firstOrNull { it.tag != "auto" }
                                ?: current.targetLanguage
                        }
                    }

                    current.copy(
                        availableServices  = selection.availableServices,
                        availableLanguages = sortedLanguages,
                        serviceOptions     = selection.serviceOptions,
                        targetLanguage     = targetLang
                    )
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeInstantTranslation() {
        // Immediately clear output when the user erases all input — no debounce.
        scope.launch {
            state.map { it.inputText }
                .distinctUntilChanged()
                .collect { text ->
                    if (settingsState.value.isInstantTranslationEnabled && text.isBlank()) {
                        translateTextUseCase.cancel()
                        _state.update {
                            it.copy(
                                translatedText = "",
                                extraOutputText = "",
                                detectedSourceLanguage = null,
                                isLoading = false
                            )
                        }
                    }
                }
        }

        // Debounced translation — only fires when there is enough text to translate.
        scope.launch {
            state.map { it.inputText }
                .debounce(AppConstants.INSTANT_TRANSLATION_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { text ->
                    if (settingsState.value.isInstantTranslationEnabled
                        && text.length >= AppConstants.INSTANT_TRANSLATE_MIN_CHARS
                    ) {
                        translateText()
                    }
                }
        }
    }

    /** Mirrors [HandleTextToSpeechUseCase.isPlaying] into [MainState.isTtsPlaying]
     *  so the UI can reactively toggle listen ↔ stop buttons. */
    private fun observeTtsPlayback() {
        scope.launch {
            handleTextToSpeechUseCase.isPlaying.collect { playing ->
                _state.update { it.copy(isTtsPlaying = playing) }
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
                // With instant translate enabled, cancel any in-flight translation immediately
                // so the loading indicator clears and the debounce can queue the next request.
                // Without this, the collect coroutine in observeInstantTranslation stays
                // suspended at join() until the current translation finishes — the user's new
                // text effectively waits in line behind the old result.
                // Capture state once to avoid reading _state.value twice (TOCTOU race).
                if (settingsState.value.isInstantTranslationEnabled && _state.value.isLoading) {
                    translateTextUseCase.cancel()
                    _state.update { s -> s.copy(isLoading = false) }
                }
            }

            is MainIntent.SelectSourceLanguage ->
                _state.update { it.copy(sourceLanguage = intent.language, detectedSourceLanguage = null) }

            is MainIntent.SelectTargetLanguage ->
                _state.update { it.copy(targetLanguage = intent.language) }

            is MainIntent.ApplyCorrection ->
                _state.update { it.copy(inputText = it.inputText.replaceFirst(intent.original, intent.suggestion)) }

            // Closing clears the pin. A pin says "keep this one around", not "and every one
            // after it" — leaving it set meant the next popup opened wearing the pinned border
            // and then auto-hid anyway, which is the worst of both.
            MainIntent.HideQuickTranslate ->
                _state.update {
                    it.copy(isQuickTranslateDialogVisible = false, isQuickTranslateDialogPinned = false)
                }

            MainIntent.ToggleQuickTranslateDialogPin ->
                // Use `it` from the update lambda — not _state.value — to avoid
                // a data race between the value read and the update being applied.
                _state.update { it.copy(isQuickTranslateDialogPinned = !it.isQuickTranslateDialogPinned) }

            MainIntent.UndoTranslation -> handleUndo()
            MainIntent.RedoTranslation -> handleRedo()
            MainIntent.CycleTargetLanguage -> handleCycleTargetLanguage()
            is MainIntent.RestoreHistoryEntry -> handleRestoreHistoryEntry(intent)
            MainIntent.ClearHistory -> scope.launch { handleClearHistory() }

            // ---- Async operations — launched on scope ----

            MainIntent.SwapLanguages -> scope.launch { swapLanguages() }
            MainIntent.CheckForUpdates -> checkForUpdates()
            is MainIntent.TranslateDocument -> startDocumentTranslation(intent)
            MainIntent.CancelDocumentTranslation -> cancelDocumentTranslation()
            MainIntent.PerformSpellCheck -> scope.launch {
                handleSpellCheck(_state.value.inputText, isEnabled = true)
            }

            is MainIntent.Translate -> scope.launch { translateText(intent.text) }

            MainIntent.CancelTranslation -> {
                translateTextUseCase.cancel()
                _state.update { it.copy(isLoading = false) }
                scope.launch {
                    updateStatusBar(StatusCode.TranslationCancelled, NotificationType.INFO, true)
                }
            }

            MainIntent.NotifyTextCopied -> scope.launch {
                updateStatusBar(StatusCode.TextCopied, NotificationType.SUCCESS, true)
            }

            MainIntent.StopTTS -> handleTextToSpeechUseCase.stop()

            is MainIntent.ReplaceWithTranslation -> scope.launch {
                handleReplaceWithTranslation(intent.selectedText)
            }

            is MainIntent.ListenToText -> scope.launch {
                handleListen(intent.textSource, intent.text)
            }

            is MainIntent.OcrAndTranslateImage -> scope.launch {
                handleOcrAndTranslate(intent)
            }

            is MainIntent.OcrAndCopyText -> scope.launch {
                handleOcrAndCopyText(intent)
            }

            is MainIntent.ShowQuickTranslate -> scope.launch {
                handleShowQuickTranslate(intent)
            }

            is MainIntent.LookupWord -> scope.launch { handleLookupWord(intent) }

            is MainIntent.ToggleDictionaryPanel -> _state.update {
                it.copy(isDictionaryPanelVisible = !it.isDictionaryPanelVisible)
            }

            is MainIntent.ShowQuickDictionary -> scope.launch {
                // Pre-set dictionaryWord so the dialog's search field is already populated
                // on the very first render — before handleLookupWord emits its own update.
                _state.update {
                    it.copy(
                        isQuickDictionaryVisible = true,
                        // A pin belongs to the popup that was pinned, not to the next one.
                        isQuickDictionaryPinned =
                            if (it.isQuickDictionaryVisible) it.isQuickDictionaryPinned else false,
                        dictionaryWord   = if (intent.selectedText.isNotBlank()) intent.selectedText else it.dictionaryWord,
                        isDictionaryLoading = intent.selectedText.isNotBlank(),
                        quickDictionaryTriggerCount = it.quickDictionaryTriggerCount + 1
                    )
                }
                if (intent.selectedText.isNotBlank()) {
                    handleLookupWord(MainIntent.LookupWord(intent.selectedText, intent.language))
                }
            }

            is MainIntent.HideQuickDictionary -> _state.update {
                it.copy(isQuickDictionaryVisible = false, isQuickDictionaryPinned = false)
            }

            is MainIntent.ToggleQuickDictionaryPin -> _state.update {
                it.copy(isQuickDictionaryPinned = !it.isQuickDictionaryPinned)
            }

            is MainIntent.SearchImages -> scope.launch { handleSearchImages(intent) }

            is MainIntent.ShowImageSearch -> scope.launch {
                // Same reason as the dictionary popup: seed the term so the field is filled on
                // the first render rather than a frame later.
                _state.update {
                    it.copy(
                        isImageSearchVisible = true,
                        isImageSearchPinned =
                            if (it.isImageSearchVisible) it.isImageSearchPinned else false,
                        imageSearchTerm = intent.selectedText.ifBlank { it.imageSearchTerm },
                        isImageSearchLoading = intent.selectedText.isNotBlank(),
                        imageSearchTriggerCount = it.imageSearchTriggerCount + 1
                    )
                }
                if (intent.selectedText.isNotBlank()) {
                    handleSearchImages(MainIntent.SearchImages(intent.selectedText, intent.language))
                }
            }

            is MainIntent.HideImageSearch -> _state.update {
                it.copy(isImageSearchVisible = false, isImageSearchPinned = false)
            }

            is MainIntent.ToggleImageSearchPin -> _state.update {
                it.copy(isImageSearchPinned = !it.isImageSearchPinned)
            }

            is MainIntent.UpdateInlineDefinition ->
                if (intent.word.isBlank()) {
                    fetchInlineDefinitionUseCase.clear { transform -> _state.update(transform) }
                } else {
                    fetchInlineDefinitionUseCase(
                        word = intent.word,
                        language = intent.language,
                        alternateWord = intent.alternateWord,
                        alternateLanguage = intent.alternateLanguage,
                        updateState = { transform -> _state.update(transform) }
                    )
                }
        }
    }

    // -------------------------------------------------------------------------
    // Async handlers
    // -------------------------------------------------------------------------

    private fun startDocumentTranslation(intent: MainIntent.TranslateDocument) {
        documentTranslationJob?.cancel()
        val generation = ++documentTranslationGeneration
        documentTranslationJob = scope.launch {
            val state = _state.value
            try {
                val output = documentTranslationUseCase(
                    DocumentTranslationRequest(
                        inputFile = intent.inputFile,
                        outputFile = intent.outputFile,
                        sourceLanguage = state.sourceLanguage,
                        targetLanguage = state.targetLanguage,
                        pdfMode = intent.pdfMode
                    )
                ) { progress ->
                    if (generation == documentTranslationGeneration) {
                        _state.update { it.copy(documentTranslationProgress = progress) }
                    }
                }
                if (generation != documentTranslationGeneration) return@launch
                _state.update { it.copy(documentTranslationProgress = null) }
                _eventChannel.send(MainEvent.DocumentTranslationCompleted(output))
            } catch (error: CancellationException) {
                if (generation == documentTranslationGeneration) {
                    _state.update { it.copy(documentTranslationProgress = null) }
                }
                throw error
            } catch (error: DocumentTranslationException) {
                if (generation != documentTranslationGeneration) return@launch
                _state.update { it.copy(documentTranslationProgress = null) }
                _eventChannel.send(MainEvent.DocumentTranslationFailed(error.message))
            } catch (error: Exception) {
                if (generation != documentTranslationGeneration) return@launch
                _state.update { it.copy(documentTranslationProgress = null) }
                _eventChannel.send(
                    MainEvent.DocumentTranslationFailed(error.message ?: "Document translation failed.")
                )
            } finally {
                if (generation == documentTranslationGeneration) {
                    documentTranslationJob = null
                }
            }
        }
    }

    private fun cancelDocumentTranslation() {
        documentTranslationGeneration++
        documentTranslationJob?.cancel(CancellationException("Document translation cancelled"))
        documentTranslationJob = null
        _state.update { it.copy(documentTranslationProgress = null) }
    }

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

    /**
     * OCR-only path: extracts text from [intent.image] and emits [MainEvent.CopyToClipboard]
     * so the UI layer can write it to the system clipboard.
     * Does NOT translate — the user chose "Copy Text", not "Translate".
     */
    private suspend fun handleOcrAndCopyText(intent: MainIntent.OcrAndCopyText) {
        val extractedText = ocrAndTranslateUseCase(
            image = intent.image,
            currentState = _state.value,
            onStatusUpdate = ::updateStatusBar
        )
        if (extractedText.isBlank()) return
        _eventChannel.send(MainEvent.CopyToClipboard(extractedText))
        updateStatusBar(StatusCode.OcrTextCopied, NotificationType.INFO, true)
    }

    private suspend fun handleShowQuickTranslate(intent: MainIntent.ShowQuickTranslate) {
        if (intent.selectedText.isBlank()) return

        if (_state.value.isQuickTranslateDialogVisible) {
            // Already open, pinned or not: replace the text and count the trigger, so the popup
            // refreshes in place and restarts its countdown. Hiding and re-showing it would
            // flicker, move it, and throw away a pin the user had set.
            _state.update {
                it.copy(
                    inputText = intent.selectedText,
                    quickTranslateTriggerCount = it.quickTranslateTriggerCount + 1
                )
            }
        } else {
            // Open a fresh popup — never pinned, whatever the last one was left as.
            //
            // isLoading and the cleared text are set here rather than left to the use case that
            // follows. The popup withholds itself until there is something to show, and it decides
            // that from this state; without it the popup saw "not loading, no text" for the moment
            // between being asked for and the request starting, took that for a finished result,
            // and opened empty — which is the loading state the user was seeing inside the popup
            // instead of the marker that should have covered the wait.
            _state.update {
                it.copy(
                    inputText = intent.selectedText,
                    translatedText = "",
                    isLoading = true,
                    isQuickTranslateDialogPinned = false,
                    isQuickTranslateDialogVisible = true,
                    quickTranslateTriggerCount = it.quickTranslateTriggerCount + 1
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

    private suspend fun handleLookupWord(intent: MainIntent.LookupWord) {
        lookupWordUseCase(
            word = intent.word,
            language = intent.language,
            targetLanguage = _state.value.targetLanguage,
            updateState = { transform -> _state.update(transform) },
            onStatusUpdate = ::updateStatusBar
        )
    }

    private suspend fun handleSearchImages(intent: MainIntent.SearchImages) {
        searchImagesUseCase(
            term = intent.term,
            language = intent.language,
            updateState = { transform -> _state.update(transform) },
            onStatusUpdate = ::updateStatusBar
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
                extraOutputText        = snapshot.extraOutputText,
                detectedSourceLanguage = snapshot.detectedSourceLanguage?.let { tag -> LanguageCode(tag) },
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
                    extraOutputText        = snapshot.extraOutputText,
                    detectedSourceLanguage = snapshot.detectedSourceLanguage?.let { tag -> LanguageCode(tag) },
                    spellCheckCorrections  = emptyList()
                )
            }
        }
    }

    private fun handleRestoreHistoryEntry(intent: MainIntent.RestoreHistoryEntry) {
        val snapshot = intent.snapshot
        val idx = _state.value.history.indexOf(snapshot)
        _state.update {
            it.copy(
                inputText              = snapshot.inputText,
                translatedText         = snapshot.translatedText,
                sourceLanguage         = LanguageCode(snapshot.sourceLanguage),
                targetLanguage         = LanguageCode(snapshot.targetLanguage),
                historyIndex           = if (idx >= 0) idx + 1 else it.historyIndex,
                isLoading              = false,
                extraOutputText        = snapshot.extraOutputText,
                detectedSourceLanguage = snapshot.detectedSourceLanguage?.let { tag -> LanguageCode(tag) },
                spellCheckCorrections  = emptyList()
            )
        }
    }

    private suspend fun handleClearHistory() {
        historyRepository.clearHistory()
        _state.update { it.copy(history = emptyList(), historyIndex = 0) }
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
        cancelDocumentTranslation()
        if (settingsState.value.clearHistoryOnExit) {
            historyRepository.clearHistory()
        }
        handleTextToSpeechUseCase.shutdown()
    }

    private suspend fun updateStatusBar(
        code: StatusCode,
        type: NotificationType,
        isTemporary: Boolean
    ) {
        _eventChannel.send(MainEvent.UpdateStatusBar(code, type, isTemporary))
    }
}
