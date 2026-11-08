package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.core.document.DocumentTranslationSession
import com.github.ahatem.qtranslate.core.document.DocumentTranslationRequest
import com.github.ahatem.qtranslate.core.document.DocumentTranslationUseCase
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.history.previousHistory
import com.github.ahatem.qtranslate.core.history.nextHistory
import com.github.ahatem.qtranslate.core.history.restoredHistoryIndex
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.core.main.domain.language.LanguageSelectionCompat
import com.github.ahatem.qtranslate.core.main.domain.usecase.*
import com.github.ahatem.qtranslate.core.ocr.OcrExtractionOutcome
import com.github.ahatem.qtranslate.core.ocr.OcrExtractionUseCase
import com.github.ahatem.qtranslate.core.ocr.OcrRequestOwner
import com.github.ahatem.qtranslate.core.ocr.preprocessOcrText
import com.github.ahatem.qtranslate.core.ocr.resolveOcrLanguage
import com.github.ahatem.qtranslate.core.workspace.translation.ComparisonPolicy
import com.github.ahatem.qtranslate.core.workspace.translation.RewriteUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.SummarizeUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.TranslateTextUseCase
import com.github.ahatem.qtranslate.core.workspace.translation.TranslationCompletion
import com.github.ahatem.qtranslate.core.workspace.translation.TranslationCoordinator
import com.github.ahatem.qtranslate.core.workspace.translation.TranslationStart
import com.github.ahatem.qtranslate.core.workspace.translation.TranslationOrigin
import com.github.ahatem.qtranslate.core.workspace.lookup.DictionaryLookupRequest
import com.github.ahatem.qtranslate.core.workspace.lookup.DictionaryLookupSession
import com.github.ahatem.qtranslate.core.workspace.lookup.FetchInlineDefinitionUseCase
import com.github.ahatem.qtranslate.core.workspace.lookup.ImageLookupRequest
import com.github.ahatem.qtranslate.core.workspace.lookup.ImageLookupSession
import com.github.ahatem.qtranslate.core.workspace.lookup.LookupWordUseCase
import com.github.ahatem.qtranslate.core.workspace.lookup.SearchImagesUseCase
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.effectiveLayoutPresetId
import com.github.ahatem.qtranslate.core.settings.data.isComparisonEligible
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputRequest
import com.github.ahatem.qtranslate.core.settings.data.OcrPostSelectionAction
import com.github.ahatem.qtranslate.core.settings.data.OcrSettings
import com.github.ahatem.qtranslate.core.settings.data.OcrTranslationDestination
import com.github.ahatem.qtranslate.core.settings.data.SelectionReadSource
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.arch.Store
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

private data class ComparisonConfigKey(
    val activePresetId: String?,
    val primaryTranslatorId: String?,
    val comparisonTranslatorIds: List<String>,
    val translationRules: List<com.github.ahatem.qtranslate.core.settings.data.TranslationRule>,
    val disabledServices: Set<String>
)

internal data class SpellCheckInput(
    val text: String,
    val sourceLanguage: LanguageCode,
    val detectedSourceLanguage: LanguageCode?,
    val isEnabled: Boolean,
) {
    fun matches(state: MainState, enabled: Boolean): Boolean = this == from(state, enabled)

    companion object {
        fun from(state: MainState, enabled: Boolean) = SpellCheckInput(
            state.inputText,
            state.sourceLanguage,
            state.detectedSourceLanguage.takeIf { state.sourceLanguage == LanguageCode.AUTO },
            enabled,
        )
    }
}

internal fun spellCheckInputs(states: Flow<MainState>, enabled: Flow<Boolean>): Flow<SpellCheckInput> =
    combine(
        states.map { SpellCheckInput.from(it, false) }.distinctUntilChanged(),
        enabled.distinctUntilChanged(),
    ) { input, isEnabled -> input.copy(isEnabled = isEnabled) }.distinctUntilChanged()

internal fun MainState.clearSpellCorrectionsForInputChange(previous: SpellCheckInput?, incoming: SpellCheckInput): MainState =
    if (previous != null && previous != incoming) copy(spellCheckCorrections = emptyList()) else this

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
 * [MainIntent.OcrAndTranslateImage] extracts text via [OcrExtractionUseCase],
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
    private val ocrExtractionUseCase: OcrExtractionUseCase,
    private val summarizeUseCase: SummarizeUseCase,
    private val rewriteUseCase: RewriteUseCase,
    private val lookupWordUseCase: LookupWordUseCase,
    private val searchImagesUseCase: SearchImagesUseCase,
    private val fetchInlineDefinitionUseCase: FetchInlineDefinitionUseCase,
    private val documentTranslationUseCase: DocumentTranslationUseCase
) : Store<MainState, MainIntent, MainEvent> {

    private val quickTranslateGenerations = AtomicLong()

    /**
     * Screen OCR has its own publication owner. Ownership and every OCR-derived state/event
     * decision share one lock, so a superseded capture can never become externally visible after
     * a newer one has taken over. Cancellation alone is not the contract: a coroutine may already
     * be inside publication.
     */
    private val ocrOwner = OcrRequestOwner()

    /**
     * The translation request this OCR workflow started, if any. A newer capture cancels it by
     * identity, not by touching whatever translation happens to be current.
     */
    @Volatile
    private var ocrTranslation: OcrTranslation? = null

    private data class OcrTranslation(val requestId: Long, val quick: Boolean)

    /**
     * Test seam at the point where an older OCR request has returned but has not published yet.
     * Production leaves it null. A deterministic race test holds one request there and lets a
     * newer workflow take ownership.
     */
    internal var beforeOcrPublication: ((token: Long) -> Unit)? = null
    internal var afterOcrPublication: ((token: Long) -> Unit)? = null

    /**
     * The input text a non-typing workflow just placed in the editor. The instant-translation
     * observer skips exactly this value once: otherwise enabling instant translation could
     * schedule a second request that supersedes the OCR/Quick/Replace translation that owns it.
     * Any user edit clears the marker by differing from it.
     */
    @Volatile
    private var programmaticInputText: String? = null

    private val _state = MutableStateFlow(
        MainState(
            isLookupDockOpen         = settingsState.value.showDictionaryPanel,
            isQuickDictionaryPinned  = settingsState.value.isQuickDictionaryPinned,
            targetLanguage           = LanguageCode(settingsState.value.preferredTargetLanguage),
            sourceLanguage           = LanguageCode(settingsState.value.preferredSourceLanguage)
        )
    )
    override val state: StateFlow<MainState> = _state.asStateFlow()

    private val _eventChannel = Channel<MainEventDelivery>(Channel.BUFFERED)
    private val translationCoordinator = TranslationCoordinator(
        useCase = translateTextUseCase,
        settingsState = settingsState,
        getState = { _state.value },
        updateState = { transform -> _state.update(transform) },
        onStatusUpdate = { code, type, isTemporary -> updateStatusBar(code, type, isTemporary) },
        emitCopy = { text, requestId ->
            sendEvent(MainEvent.CopyToClipboard(text)) { publish ->
                translateTextUseCase.withCurrentRequest(requestId, publish)
            }
        }
    )

    /**
     * The single UI consumer transports these envelopes to Swing before accepting effects.
     * Collect either this flow or [events], never both: they share the same one-shot channel.
     */
    val eventDeliveries: Flow<MainEventDelivery> = _eventChannel.receiveAsFlow()

    /** Compatibility view for consumers that deliver immediately on their collection dispatcher. */
    override val events: Flow<MainEvent> = eventDeliveries.mapNotNull { it.accept() }

    private val documentSession = DocumentTranslationSession(
        scope = scope,
        translate = { request, progress -> documentTranslationUseCase(request, progress) },
        onProgress = { progress -> _state.update { it.copy(documentTranslationProgress = progress) } },
        onCompleted = { output, publish -> sendEvent(MainEvent.DocumentTranslationCompleted(output), publish) },
        onFailed = { message, publish -> sendEvent(MainEvent.DocumentTranslationFailed(message), publish) }
    )

    private val dictionarySession = DictionaryLookupSession(
        scope, { request, started -> lookupWordUseCase(request, started) },
        onStarted = { request, publish ->
            sendEvent(MainEvent.UpdateStatusBar(StatusCode.LookingUpWord, NotificationType.INFO, false), publish)
            publish { _state.update { it.copy(isDictionaryLoading = true, dictionaryWord = request.word,
                dictionaryLanguage = request.language, dictionaryFailed = false) } }
        },
        onResult = { outcome, publish ->
            sendEvent(MainEvent.UpdateStatusBar(outcome.status, outcome.type, true), publish)
            if (outcome.entries != null) publish { _state.update { it.copy(
                isDictionaryLoading = false, dictionaryEntries = outcome.entries,
                dictionaryFailed = outcome.failed ?: it.dictionaryFailed) } }
        }
    )
    private val imageSession = ImageLookupSession(
        scope, { request, started -> searchImagesUseCase(request, started) },
        onStarted = { request, publish ->
            sendEvent(MainEvent.UpdateStatusBar(StatusCode.SearchingImages, NotificationType.INFO, false), publish)
            publish { _state.update { it.copy(isImageSearchLoading = true, imageSearchTerm = request.term,
                imageSearchFailed = false) } }
        },
        onResult = { outcome, publish ->
            sendEvent(MainEvent.UpdateStatusBar(outcome.status, outcome.type, true), publish)
            if (outcome.results != null) publish { _state.update { it.copy(
                isImageSearchLoading = false, imageResults = outcome.results,
                imageSearchFailed = outcome.failed ?: it.imageSearchFailed) } }
        }
    )

    init {
        loadInitialHistory()
        observeAvailableServices()
        observeInstantTranslation()
        observeSpellChecking()
        observeTtsPlayback()
        observeComparisonConfiguration()
        observeComparisonEligibility()
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
                    val advertised = LanguageSelectionCompat.visibleLanguages(
                        selection.availableLanguages,
                        listOf(current.targetLanguage, current.sourceLanguage)
                    )
                    val sortedLanguages = advertised.sortedWith(
                        compareBy<LanguageCode> { lc -> lc.tag != "auto" }
                            .thenBy { lc -> lc.getDisplayName() }
                    )

                    // If the previously selected target language is not available in the new
                    // list (e.g. because pinnedLanguages hides it, or the translator doesn't
                    // support it), fall back to the saved preference then to the first available
                    // non-auto language so the user is never silently translated to the wrong language.
                    val targetLang = LanguageSelectionCompat.resolveTargetLanguage(
                        current.targetLanguage,
                        sortedLanguages,
                        settingsState.value.preferredTargetLanguage
                    )

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
                        cancelTranslationWork()
                        _state.update {
                            it.copy(
                                translatedText = "",
                                translationFailed = false,
                                comparisonResults = emptyList(),
                                extraOutputText = "",
                                extraOutputFailed = false,
                                extraOutputFailure = null,
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
                    if (text == programmaticInputText) {
                        // One workflow owns this text. Consume the marker so a later identical
                        // user edit is not suppressed as well.
                        programmaticInputText = null
                        return@collect
                    }
                    programmaticInputText = null
                    if (settingsState.value.isInstantTranslationEnabled
                        && text.length >= AppConstants.INSTANT_TRANSLATE_MIN_CHARS
                    ) {
                        // Instant translation happens in the main window, so it follows the main
                        // window's Comparison policy (#326). Running it with the policy disabled
                        // wiped the comparison rows of the layout and never put them back, which
                        // left the Comparison board showing the primary translator alone.
                        translateText(TranslationOrigin.INSTANT, comparisonPolicy = mainComparisonPolicy())
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

    private fun observeComparisonConfiguration() {
        scope.launch {
            settingsState
                .map { config ->
                    val preset = config.getActivePreset()
                    ComparisonConfigKey(
                        activePresetId = config.activeServicePresetId,
                        primaryTranslatorId = preset?.selectedServices?.get(ServiceRole.TRANSLATOR),
                        comparisonTranslatorIds = preset?.comparisonTranslatorIds.orEmpty(),
                        translationRules = config.translationRules,
                        disabledServices = config.disabledServices
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { clearComparisonState() }
        }
    }

    /**
     * Announces the deterministic Classic fallback once per entry: a requested
     * Comparison with fewer than two effective translators shows Classic
     * without rewriting the saved preference. Edge-triggered, so staying
     * ineligible never spams; recovering eligibility re-arms the notice.
     * Fires on subscribe too, covering startup with a stale saved layout.
     */
    private var comparisonFallbackAnnounced = false

    private fun observeComparisonEligibility() {
        scope.launch {
            combine(
                settingsState,
                state.map { it.availableServices }
            ) { config, services ->
                val availableIds = services
                    .filter { it.type == ServiceRole.TRANSLATOR }
                    .map { it.id }
                config.layoutPresetId == LayoutPresetIds.COMPARISON &&
                    !config.isComparisonEligible(availableIds)
            }
                .distinctUntilChanged()
                .collect { needsFallback ->
                    if (shouldAnnounceComparisonFallback(needsFallback, comparisonFallbackAnnounced)) {
                        comparisonFallbackAnnounced = true
                        updateStatusBar(
                            StatusCode.ComparisonNeedsTwoTranslators,
                            NotificationType.WARNING,
                            true
                        )
                    } else if (!needsFallback) {
                        comparisonFallbackAnnounced = false
                    }
                }
        }
    }

    private fun availableTranslatorIds(): List<String> = _state.value.availableTranslatorIds

    private fun mainComparisonPolicy(): ComparisonPolicy {
        val config = settingsState.value
        return mainTranslationComparisonPolicy(config.effectiveLayoutPresetId(availableTranslatorIds()))
    }

    private fun quickComparisonPolicy(): ComparisonPolicy =
        quickTranslationComparisonPolicy(settingsState.value.isComparisonEligible(availableTranslatorIds()))

    @OptIn(FlowPreview::class)
    private fun observeSpellChecking() {
        scope.launch {
            var previous: SpellCheckInput? = null
            spellCheckInputs(state, settingsState.map { it.isSpellCheckingEnabled })
                .onEach { input ->
                    _state.update { it.clearSpellCorrectionsForInputChange(previous, input) }
                    previous = input
                }
                .debounce(AppConstants.SPELL_CHECK_DEBOUNCE_MS)
                .collectLatest(::handleSpellCheck)
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
                // The user is editing the input themselves; a previous programmatic write no
                // longer owns the instant-translation suppression slot.
                programmaticInputText = null
                // Remove line breaks if enabled — replaces \n with space so
                // PDF-copied text translates as complete sentences (Mohamed's request)
                val cleaned = prepareInputText(intent.text, settingsState.value.isRemoveLineBreaksEnabled)
                clearComparisonState()
                _state.update { it.copy(inputText = cleaned, detectedSourceLanguage = null, spellCheckCorrections = emptyList()) }
                // With instant translate enabled, cancel any in-flight translation immediately
                // so the loading indicator clears and the debounce can queue the next request.
                // Without this, the collect coroutine in observeInstantTranslation stays
                // suspended at join() until the current translation finishes — the user's new
                // text effectively waits in line behind the old result.
                // Capture state once to avoid reading _state.value twice (TOCTOU race).
                if (settingsState.value.isInstantTranslationEnabled && _state.value.isLoading) {
                    cancelTranslationWork()
                    _state.update { s -> s.copy(isLoading = false) }
                }
            }

            is MainIntent.SelectSourceLanguage ->
                run {
                    clearComparisonState()
                    _state.update { it.copy(sourceLanguage = intent.language, detectedSourceLanguage = null, spellCheckCorrections = emptyList()) }
                }

            is MainIntent.SelectTargetLanguage ->
                run {
                    clearComparisonState()
                    _state.update { it.copy(targetLanguage = intent.language) }
                }

            is MainIntent.ApplyCorrection ->
                run {
                    clearComparisonState()
                    _state.update { current ->
                        val replacement = if (intent.correction in current.spellCheckCorrections)
                            replaceCorrectionAtRange(current.inputText, intent.correction, intent.suggestion)
                        else null
                        replacement?.let { current.copy(inputText = it, spellCheckCorrections = emptyList()) } ?: current
                    }
                }

            // Closing clears the pin. A pin says "keep this one around", not "and every one
            // after it" — leaving it set meant the next popup opened wearing the pinned border
            // and then auto-hid anyway, which is the worst of both.
            MainIntent.HideQuickTranslate -> {
                quickTranslateGenerations.incrementAndGet()
                // Quick shares its result with the main window, so closing the popup keeps a
                // finished translation and its comparisons. Work still running is cancelled, with
                // ownership invalidated so a late result cannot land afterwards. Comparison rows
                // are cleared only while the primary or a comparison is unfinished: extra output
                // alone is published after them and does not make a finished set incomplete.
                val close = _state.value.quickCloseActions()
                if (close.cancelWork) cancelTranslationWork()
                if (close.clearComparisons) clearComparisonState()
                _state.update { it.afterQuickClose() }
            }

            MainIntent.ToggleQuickTranslateDialogPin ->
                // Use `it` from the update lambda — not _state.value — to avoid
                // a data race between the value read and the update being applied.
                _state.update { it.copy(isQuickTranslateDialogPinned = !it.isQuickTranslateDialogPinned) }

            MainIntent.RetranslateQuickTranslate -> {
                cancelTranslationWork()
                clearComparisonState()
                scope.launch { translateText(TranslationOrigin.QUICK, comparisonPolicy = quickComparisonPolicy()) }
            }

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
                handleSpellCheck(SpellCheckInput.from(_state.value, true), checkSetting = false)
            }

            is MainIntent.Translate -> {
                val workspaceToken = intent.ocrWorkspaceToken
                if (workspaceToken != null && !ocrOwner.isCurrent(workspaceToken)) return
                // An explicit translation of externally supplied text owns the input for this
                // pass; with instant translation enabled, the debounced observer must not replace
                // it with an otherwise identical secondary request.
                if (intent.text != null) {
                    val currentInput = _state.value.inputText
                    programmaticInputText = if (intent.text == currentInput) {
                        // Canonical sequence UpdateInputText -> Translate: the editor already
                        // holds the normalized value, so the marker must match it exactly.
                        currentInput
                    } else {
                        prepareInputText(
                            intent.text,
                            settingsState.value.isRemoveLineBreaksEnabled,
                        )
                    }
                }
                cancelTranslationWork()
                clearComparisonState()
                scope.launch {
                    translateText(
                        TranslationOrigin.MAIN,
                        intent.text,
                        comparisonPolicy = mainComparisonPolicy(),
                        onRequestClaimed = { claimedId ->
                            if (workspaceToken != null) {
                                val filed = ocrOwner.withCurrent(workspaceToken) {
                                    ocrTranslation = OcrTranslation(claimedId, quick = false)
                                }
                                if (filed == null) {
                                    // Stale: a newer OCR workflow claimed ownership after
                                    // our pre-check. Do not let this orphan start.
                                    translationCoordinator.cancelIfCurrent(claimedId)
                                }
                            }
                            intent.onTranslationClaimed?.invoke(claimedId)
                        },
                    )
                }
            }

            is MainIntent.RefreshExtraOutput -> scope.launch {
                refreshExtraOutput(intent.extraOutputRequest)
            }

            MainIntent.CancelTranslation -> {
                cancelTranslationWork()
                clearComparisonState()
                // Both flags, because cancelling can land while the extra panel is still waiting
                // on its own request and only the main one was ever cleared here.
                _state.update { it.copy(isLoading = false) }
                scope.launch {
                    updateStatusBar(StatusCode.TranslationCancelled, NotificationType.INFO, true)
                }
            }

            is MainIntent.CancelTranslationIfCurrent -> {
                // Ownership-gated cleanup: only the named request may be cancelled.
                // A null result means a newer owner holds the result, so do nothing —
                // never infer ownership from input text or loading state.
                val cancellation = translationCoordinator.cancelIfCurrent(intent.requestId) ?: return
                translationCoordinator.withCurrentRequest(cancellation) {
                    _state.update { it.copy(isLoading = false) }
                }
            }

            MainIntent.NotifyTextCopied -> scope.launch {
                updateStatusBar(StatusCode.TextCopied, NotificationType.SUCCESS, true)
            }

            MainIntent.StopTTS -> handleTextToSpeechUseCase.stop()

            is MainIntent.ReplaceWithTranslation -> {
                clearComparisonState()
                scope.launch { handleReplaceWithTranslation(intent.selectedText) }
            }

            is MainIntent.ListenToText -> scope.launch {
                handleListen(intent.textSource, intent.text, intent.language)
            }

            is MainIntent.OcrWorkflowImage -> startOcrWorkflow(
                image = intent.image,
                action = intent.action,
                destinationOverride = intent.destinationOverride,
            )

            is MainIntent.OcrAndTranslateImage -> startOcrWorkflow(
                image = intent.image,
                action = OcrPostSelectionAction.TRANSLATE,
                destinationOverride = intent.destination,
            )

            is MainIntent.OcrAndCopyText -> startOcrWorkflow(
                image = intent.image,
                action = OcrPostSelectionAction.COPY_TEXT,
                destinationOverride = null,
            )

            is MainIntent.ShowQuickTranslate -> startQuickTranslate(intent)

            is MainIntent.LookupWord -> scope.launch { handleLookupWord(intent) }

            is MainIntent.ToggleDictionaryPanel -> _state.update { it.withDictionaryPanelToggled() }

            is MainIntent.OpenLookupDock -> _state.update { it.withLookupDockOn(intent.tool) }

            is MainIntent.SelectLookupTool -> _state.update { it.copy(lookupDockTool = intent.tool) }

            is MainIntent.CloseLookupDock -> _state.update { it.copy(isLookupDockOpen = false) }

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
        val state = _state.value
        documentSession.start(
            DocumentTranslationRequest(
                inputFile = intent.inputFile,
                outputFile = intent.outputFile,
                sourceLanguage = state.sourceLanguage,
                targetLanguage = state.targetLanguage,
                pdfMode = intent.pdfMode
            )
        )
    }

    private fun cancelDocumentTranslation() = documentSession.cancel()

    /**
     * Starts one capture workflow and makes it the only OCR request allowed to publish.
     *
     * Copy Image never waits for a provider, but it still runs through the same fenced event
     * owner: choosing it is a new workflow action, and an older capture must not publish after it.
     */
    private fun startOcrWorkflow(
        image: com.github.ahatem.qtranslate.api.ocr.ImageData,
        action: OcrPostSelectionAction,
        destinationOverride: OcrTranslationDestination?,
    ) {
        var previousTranslation: OcrTranslation? = null
        val claim = ocrOwner.claim {
            previousTranslation = ocrTranslation
            ocrTranslation = null
        } ?: return
        // Only identities detached by this claim may be cancelled. A newer claim can install
        // its translation while cancellation invokes handlers, so never reread the shared slot.
        claim.previousJob?.cancel()
        cancelOcrTranslation(previousTranslation)
        val token = claim.token

        when (action) {
            OcrPostSelectionAction.COPY_IMAGE -> startOwnedOcrJob(token) {
                publishOcrEvent(token, MainEvent.CopyImageToClipboard(image))
                publishOcrStatus(token, StatusCode.OcrImageCopied, NotificationType.SUCCESS, true)
            }
            OcrPostSelectionAction.OPEN_WORKSPACE -> startOwnedOcrJob(token) {
                publishOcrEvent(
                    token,
                    MainEvent.OpenOcrWorkspace(image = image, autoTranslate = false, ocrToken = token),
                )
            }
            OcrPostSelectionAction.SHOW_ACTIONS -> Unit
            OcrPostSelectionAction.TRANSLATE,
            OcrPostSelectionAction.COPY_TEXT -> startOwnedOcrJob(token) {
                handleOcrWorkflow(image, action, destinationOverride, token)
            }
        }
    }

    /**
     * Installs the job slot before the lazy body runs. If a newer workflow already owns OCR by
     * the time the job is created, the candidate is detached and never starts.
     */
    private fun startOwnedOcrJob(token: Long, block: suspend () -> Unit): Job {
        val candidate = scope.launch(start = CoroutineStart.LAZY) { block() }
        if (!ocrOwner.install(token, candidate)) {
            candidate.cancel()
            return candidate
        }
        candidate.start()
        return candidate
    }

    private suspend fun handleOcrWorkflow(
        image: com.github.ahatem.qtranslate.api.ocr.ImageData,
        action: OcrPostSelectionAction,
        destinationOverride: OcrTranslationDestination?,
        token: Long,
    ) {
        val settings = settingsState.value.ocr
        val language = resolveOcrLanguage(settings.recognitionLanguage, _state.value.sourceLanguage)
        val outcome = ocrExtractionUseCase.extract(
            image = image,
            language = language,
            onStatusUpdate = { code, type, isTemporary ->
                publishOcrStatus(token, code, type, isTemporary)
            },
        )

        beforeOcrPublication?.invoke(token)
        val publication = ocrOwner.withCurrent(token) {
            publishOcrOutcome(
                image = image,
                action = action,
                destinationOverride = destinationOverride,
                settings = settings,
                language = language,
                outcome = outcome,
                token = token,
            )
        }
        if (publication == null) {
            afterOcrPublication?.invoke(token)
            return
        }
        // Admission detached old jobs. Release them before any test hook, queue send or other
        // suspension can cancel this coroutine and strand work outside its owner's job slot.
        publication.translationStart?.cancelPrevious()
        afterOcrPublication?.invoke(token)

        publication.events.forEach { event ->
            publishOcrEvent(token, event)
        }
        val start = publication.translationStart ?: return
        if (publication.quickText != null) {
            startQuickTranslate(MainIntent.ShowQuickTranslate(publication.quickText), start)
        } else {
            scope.launch {
                translateText(
                    origin = TranslationOrigin.OCR,
                    textOverride = publication.mainText,
                    comparisonPolicy = ComparisonPolicy.DISABLED,
                    start = start,
                )
            }
        }
    }

    /**
     * The publication decision and every main-state write it owns happen while [token] is still
     * current. Events are returned and only queued after the ownership block, still fenced by the
     * same token so a newer workflow drops them at consumption time too.
     */
    private fun publishOcrOutcome(
        image: com.github.ahatem.qtranslate.api.ocr.ImageData,
        action: OcrPostSelectionAction,
        destinationOverride: OcrTranslationDestination?,
        settings: OcrSettings,
        language: LanguageCode,
        outcome: OcrExtractionOutcome,
        token: Long,
    ): OcrPublication {
        val result = (outcome as? OcrExtractionOutcome.Success)?.result
        val preparedText = result?.let { preprocessOcrText(it.text, settings) }.orEmpty()
        if (preparedText.isBlank()) {
            // The capture overlay has already closed and the main window was hidden for it.
            // A recognition failure must not strand the user on an invisible window with only
            // a hidden status-bar message. Workspace actions restore their own window.
            return if (action == OcrPostSelectionAction.TRANSLATE ||
                action == OcrPostSelectionAction.COPY_TEXT
            ) {
                OcrPublication(events = listOf(MainEvent.ShowMainWindow))
            } else {
                OcrPublication()
            }
        }
        val recognized = requireNotNull(result)

        return when (action) {
            OcrPostSelectionAction.COPY_TEXT -> OcrPublication(
                events = listOf(
                    MainEvent.CopyToClipboard(preparedText),
                    MainEvent.UpdateStatusBar(StatusCode.OcrTextCopied, NotificationType.INFO, true),
                )
            )
            OcrPostSelectionAction.TRANSLATE -> when (destinationOverride ?: settings.translationDestination) {
                OcrTranslationDestination.QUICK_TRANSLATE -> {
                    val start = translationCoordinator.reserveStart()
                    ocrTranslation = OcrTranslation(start.requestId, quick = true)
                    OcrPublication(translationStart = start, quickText = preparedText)
                }

                OcrTranslationDestination.OCR_WORKSPACE -> OcrPublication(
                    events = listOf(
                        MainEvent.OpenOcrWorkspace(
                            image = image,
                            autoTranslate = true,
                            initialText = preparedText,
                            initialRegions = recognized.regions,
                            initialLanguage = recognized.detectedLanguage
                                ?.takeIf { it != LanguageCode.AUTO }
                                ?: language.takeIf { it != LanguageCode.AUTO },
                            ocrToken = token,
                        )
                    )
                )
                OcrTranslationDestination.MAIN_WINDOW -> {
                    // The main input's own line-break policy still applies, exactly as it
                    // does to text typed or pasted there. Quick Translate applies it too.
                    val mainText = prepareInputText(
                        preparedText,
                        settingsState.value.isRemoveLineBreaksEnabled,
                    )
                    val start = translationCoordinator.reserveStart()
                    ocrTranslation = OcrTranslation(start.requestId, quick = false)
                    programmaticInputText = mainText
                    _state.update {
                        it.copy(
                            comparisonResults = emptyList(),
                            inputText = mainText,
                            detectedSourceLanguage = null,
                            spellCheckCorrections = emptyList(),
                        )
                    }
                    OcrPublication(
                        events = listOf(MainEvent.ShowMainWindow),
                        translationStart = start,
                        mainText = mainText,
                    )
                }
            }
            OcrPostSelectionAction.COPY_IMAGE,
            OcrPostSelectionAction.OPEN_WORKSPACE,
            OcrPostSelectionAction.SHOW_ACTIONS -> OcrPublication()
        }
    }

    private data class OcrPublication(
        val events: List<MainEvent> = emptyList(),
        val translationStart: TranslationStart? = null,
        val quickText: String? = null,
        val mainText: String? = null,
    )

    /**
     * Queues an OCR-derived event behind an ownership check at consumption time. Even if the
     * event was buffered before a newer workflow claimed OCR, the newer claim makes the publish
     * action a no-op and the event is dropped instead of reaching the UI.
     */
    private suspend fun publishOcrEvent(token: Long, event: MainEvent) {
        sendEvent(event) { publish ->
            ocrOwner.withCurrent(token) { publish() }
        }
    }

    private suspend fun publishOcrStatus(
        token: Long,
        code: StatusCode,
        type: NotificationType,
        isTemporary: Boolean,
    ) {
        publishOcrEvent(token, MainEvent.UpdateStatusBar(code, type, isTemporary))
    }

    /**
     * Cancels a translation only while the OCR workflow that started it still owns it. A manual
     * translation that has since claimed the workspace is left alone, and the loading/comparison
     * state the OCR path owned is normalized even when no replacement translation starts.
     */
    private fun cancelOcrTranslation(translation: OcrTranslation?) {
        if (translation == null) return
        val wasLoading = _state.value.isLoading
        val cancellation = translationCoordinator.cancelIfCurrent(translation.requestId) ?: return
        // A cancellation handler may have admitted a manual request. Only the cancellation's
        // replacement identity may normalize flags; it cannot hide or clear a newer popup.
        translationCoordinator.withCurrentRequest(cancellation) {
            _state.update { current ->
                current.copy(
                    comparisonResults = if (translation.quick) emptyList() else current.comparisonResults,
                    isLoading = false,
                    isQuickTranslateDialogVisible = if (wasLoading && translation.quick) false else current.isQuickTranslateDialogVisible,
                    isQuickTranslateDialogPinned = if (wasLoading && translation.quick) false else current.isQuickTranslateDialogPinned,
                )
            }
        }
    }

    /** Reuses canonical Quick startup; an OCR admission has already claimed translation. */
    private fun startQuickTranslate(intent: MainIntent.ShowQuickTranslate, start: TranslationStart? = null) {
        val generation: Long
        if (start == null) {
            programmaticInputText = prepareInputText(intent.selectedText, settingsState.value.isRemoveLineBreaksEnabled)
            generation = quickTranslateGenerations.incrementAndGet()
            cancelTranslationWork()
            clearComparisonState()
        } else {
            start.cancelPrevious()
            generation = translationCoordinator.withCurrentRequest(start.requestId) {
                programmaticInputText = prepareInputText(intent.selectedText, settingsState.value.isRemoveLineBreaksEnabled)
                quickTranslateGenerations.incrementAndGet()
            } ?: return
        }
        scope.launch { handleShowQuickTranslate(intent, generation, start) }
    }

    private suspend fun handleShowQuickTranslate(
        intent: MainIntent.ShowQuickTranslate,
        generation: Long,
        start: TranslationStart? = null,
    ) {
        if (intent.selectedText.isBlank()) return

        // #353: the "Remove line breaks from input" setting must also apply to the Quick
        // Translate translation input. Prepared once here so the first request, the stored
        // source, and any retranslate all share the same text the translator receives.
        // Raw selection handling for unrelated actions (dictionary, images, TTS source read)
        // is intentionally left untouched below.
        val preparedText = prepareInputText(intent.selectedText, settingsState.value.isRemoveLineBreaksEnabled)

        val showPopup = {
            if (_state.value.isQuickTranslateDialogVisible) {
                // Already open, pinned or not: replace the text and count the trigger, so the popup
                // refreshes in place and restarts its countdown. Hiding and re-showing it would
                // flicker, move it, and throw away a pin the user had set.
                _state.update {
                    it.copy(
                        inputText = preparedText,
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
                        inputText = preparedText,
                        translatedText = "",
                        translationFailed = false,
                        isLoading = true,
                        isQuickTranslateDialogPinned = false,
                        isQuickTranslateDialogVisible = true,
                        quickTranslateTriggerCount = it.quickTranslateTriggerCount + 1
                    )
                }
            }
        }
        if (start == null) showPopup()
        else if (translationCoordinator.withCurrentRequest(start.requestId, showPopup) == null) return

        val readSource = settingsState.value.selectionReadSource
        if (intent.readSelectionAloud && readSource == SelectionReadSource.SOURCE) {
            val sourceLanguage = _state.value.resolvedSourceLanguage
            scope.launch {
                if (!SelectionTranslationReadGuard.shouldReadSource(
                        readRequested = true,
                        selectionGeneration = generation,
                        currentSelectionGeneration = quickTranslateGenerations.get(),
                        selectedText = intent.selectedText
                    )
                ) return@launch

                handleListen(
                    textSource = TextSource.Input,
                    textOverride = intent.selectedText,
                    languageOverride = sourceLanguage,
                    requestStillCurrent = {
                        SelectionTranslationReadGuard.shouldReadSource(
                            readRequested = true,
                            selectionGeneration = generation,
                            currentSelectionGeneration = quickTranslateGenerations.get(),
                            selectedText = intent.selectedText
                        )
                    }
                )
            }
        }

        // Let TranslateTextUseCase own isLoading — it sets it at the start of the job.
        val completion = translateText(
            origin = TranslationOrigin.QUICK,
            comparisonPolicy = quickComparisonPolicy(),
            onRequestClaimed = intent.onTranslationClaimed,
            start = start,
        )
        if (readSource != SelectionReadSource.TRANSLATION ||
            !SelectionTranslationReadGuard.shouldRead(
                readRequested = intent.readSelectionAloud,
                selectionGeneration = generation,
                currentSelectionGeneration = quickTranslateGenerations.get(),
                completion = completion,
                translationStillCurrent = completion?.let { translationCoordinator.isCurrent(it.requestId) } == true
            )) return

        val speechPlan = selectionSpeechPlan(
            readSource = readSource,
            selectedText = intent.selectedText,
            completion = completion,
            state = _state.value
        ) ?: return

        // Route through the existing TTS path. The completion token proves that a translation
        // result belongs to this request rather than a newer translation.
        handleListen(
            textSource = speechPlan.textSource,
            textOverride = speechPlan.textOverride,
            languageOverride = speechPlan.languageOverride,
            requestStillCurrent = {
                generation == quickTranslateGenerations.get() &&
                    completion?.let { translationCoordinator.isCurrent(it.requestId) } == true
            }
        )
    }

    /**
     * Runs one translation and hands a covered result to the UI as [MainEvent.CopyToClipboard].
     *
     * The decision reads the completion rather than the state, because a Quick result and a
     * main-window result land in the same field. The completion is returned unchanged; callers
     * need it to prove a result is theirs.
     */
    private suspend fun translateText(
        origin: TranslationOrigin,
        textOverride: String? = null,
        extraOutputRequest: ExtraOutputRequest? = null,
        comparisonPolicy: ComparisonPolicy = ComparisonPolicy.DISABLED,
        onRequestClaimed: ((Long) -> Unit)? = null,
        start: TranslationStart? = null,
    ): TranslationCompletion? {
        return translationCoordinator.translate(
            origin = origin,
            textOverride = textOverride,
            extraOutputRequest = extraOutputRequest,
            comparisonPolicy = comparisonPolicy,
            onRequestClaimed = onRequestClaimed,
            start = start,
        )
    }

    /**
     * Recomputes the extra panel alone, falling back to a full translation when there is no
     * translation yet to derive one from.
     */
    private suspend fun refreshExtraOutput(extraOutputRequest: ExtraOutputRequest?) {
        translationCoordinator.refreshExtraOutput(extraOutputRequest)
    }

    private fun handleLookupWord(intent: MainIntent.LookupWord) {
        dictionarySession.start(DictionaryLookupRequest(
            intent.word, intent.language, intent.targetLanguage ?: _state.value.targetLanguage
        ))
    }

    private fun handleSearchImages(intent: MainIntent.SearchImages) {
        imageSession.start(ImageLookupRequest(intent.term, intent.language))
    }

    private suspend fun handleListen(
        textSource: TextSource,
        textOverride: String?,
        languageOverride: LanguageCode?,
        requestStillCurrent: () -> Boolean = { true }
    ) {
        handleTextToSpeechUseCase(
            currentState     = _state.value,
            textSource       = textSource,
            textOverride     = textOverride,
            languageOverride = languageOverride,
            onStatusUpdate   = ::updateStatusBar,
            requestStillCurrent = requestStillCurrent
        )
    }

    private suspend fun handleSpellCheck(input: SpellCheckInput, checkSetting: Boolean = true) {
        val snapshot = _state.value
        if (!input.matches(snapshot, if (checkSetting) settingsState.value.isSpellCheckingEnabled else true)) return
        val corrections = if (input.isEnabled && input.text.isNotBlank()) {
            performSpellCheckUseCase(
                currentState   = snapshot,
                text           = input.text,
                onStatusUpdate = ::updateStatusBar
            )
        } else {
            emptyList()
        }
        _state.update { current ->
            if (input.matches(current, if (checkSetting) settingsState.value.isSpellCheckingEnabled else true))
                current.copy(spellCheckCorrections = corrections)
            else current
        }
    }

    // -------------------------------------------------------------------------
    // Synchronous handlers
    // -------------------------------------------------------------------------

    private fun swapLanguages() {
        clearComparisonState()
        swapLanguagesUseCase(
            currentState     = _state.value,
            onStateUpdate    = { newState -> _state.value = newState },
            onTranslateNeeded = {
                dispatch(
                    if (_state.value.isQuickTranslateDialogVisible) MainIntent.RetranslateQuickTranslate
                    else MainIntent.Translate()
                )
            }
        )
    }

    private fun handleUndo() {
        val current = _state.value
        val selection = previousHistory(current.history, current.historyIndex) ?: return
        translationCoordinator.cancelExtraOutput()
        clearComparisonState()

        val newIndex = selection.index
        val snapshot = requireNotNull(selection.snapshot)

        _state.update {
            it.copy(
                inputText              = snapshot.inputText,
                translatedText         = snapshot.translatedText,
                translationFailed      = false,
                sourceLanguage         = LanguageCode(snapshot.sourceLanguage),
                targetLanguage         = LanguageCode(snapshot.targetLanguage),
                historyIndex           = newIndex,
                isLoading              = false,
                extraOutputText        = snapshot.extraOutputText,
                extraOutputFailed      = false,
                extraOutputFailure    = null,
                isExtraOutputLoading   = false,
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
        val selection = nextHistory(current.history, current.historyIndex) ?: return
        translationCoordinator.cancelExtraOutput()
        clearComparisonState()

        val newIndex = selection.index

        if (newIndex == current.history.size) {
            // Past the last snapshot — restore blank state.
            _state.update {
                it.copy(
                    inputText              = "",
                    translatedText         = "",
                    translationFailed      = false,
                    extraOutputText        = "",
                    extraOutputFailed      = false,
                    extraOutputFailure     = null,
                    isExtraOutputLoading   = false,
                    detectedSourceLanguage = null,
                    spellCheckCorrections  = emptyList(),
                    historyIndex           = newIndex
                )
            }
        } else {
            val snapshot = requireNotNull(selection.snapshot)
            _state.update {
                it.copy(
                    inputText              = snapshot.inputText,
                    translatedText         = snapshot.translatedText,
                    translationFailed      = false,
                    sourceLanguage         = LanguageCode(snapshot.sourceLanguage),
                    targetLanguage         = LanguageCode(snapshot.targetLanguage),
                    historyIndex           = newIndex,
                    isLoading              = false,
                    extraOutputText        = snapshot.extraOutputText,
                    extraOutputFailed      = false,
                    extraOutputFailure     = null,
                    isExtraOutputLoading   = false,
                    detectedSourceLanguage = snapshot.detectedSourceLanguage?.let { tag -> LanguageCode(tag) },
                    spellCheckCorrections  = emptyList()
                )
            }
        }
    }

    private fun handleRestoreHistoryEntry(intent: MainIntent.RestoreHistoryEntry) {
        val snapshot = intent.snapshot
        val current = _state.value
        translationCoordinator.cancelExtraOutput()
        clearComparisonState()
        _state.update {
            it.copy(
                inputText              = snapshot.inputText,
                translatedText         = snapshot.translatedText,
                translationFailed      = false,
                sourceLanguage         = LanguageCode(snapshot.sourceLanguage),
                targetLanguage         = LanguageCode(snapshot.targetLanguage),
                historyIndex           = restoredHistoryIndex(current.history, it.historyIndex, snapshot),
                isLoading              = false,
                extraOutputText        = snapshot.extraOutputText,
                extraOutputFailed      = false,
                extraOutputFailure    = null,
                isExtraOutputLoading   = false,
                detectedSourceLanguage = snapshot.detectedSourceLanguage?.let { tag -> LanguageCode(tag) },
                spellCheckCorrections  = emptyList()
            )
        }
    }

    /**
     * Cancels the primary and every secondary request, normalizing the panel flags the request
     * owned. Cancel paths otherwise leave a spinner or an old failure placeholder behind.
     */
    private fun cancelTranslationWork() = translationCoordinator.cancel()

    private suspend fun handleClearHistory() {
        historyRepository.clearHistory().fold(
            success = { _state.update { it.copy(history = emptyList(), historyIndex = 0) } },
            // The repository already records the failure; retain the visible history for retry.
            failure = { }
        )
    }

    private suspend fun handleReplaceWithTranslation(selectedText: String) {
        if (selectedText.isBlank()) return

        // #381: the "Remove line breaks from input" setting applies here too. Prepare the
        // translation input with the shared policy, exactly like normal input and Quick
        // Translate. The external selection itself is untouched: the replacement is still a
        // paste of the translated result into the source application.
        val preparedText = prepareInputText(selectedText, settingsState.value.isRemoveLineBreaksEnabled)

        // isReplacingSelection=true tells the LoadingIndicator observer to show
        // even when the main window is visible. focusableWindowState=false on
        // LoadingIndicator means it never steals focus from the source app.
        clearComparisonState()
        programmaticInputText = preparedText
        _state.update { it.copy(inputText = preparedText, isReplacingSelection = true) }
        translationCoordinator.translate(
            origin = TranslationOrigin.MAIN,
            textOverride = preparedText,
            comparisonPolicy = ComparisonPolicy.DISABLED,
            autoCopy = false,
        )
        val result = _state.value.translatedText
        _state.update { it.copy(isReplacingSelection = false) }
        if (result.isNotBlank()) {
            sendEvent(MainEvent.PasteTranslation(result))
        }
    }

    private fun handleCycleTargetLanguage() {
        val languages = _state.value.availableLanguages.filter { it != LanguageCode.AUTO }
        if (languages.isEmpty()) return
        val current = _state.value.targetLanguage
        val currentIdx = languages.indexOf(current)
        val nextIdx = (currentIdx + 1) % languages.size
        clearComparisonState()
        _state.update { it.copy(targetLanguage = languages[nextIdx]) }
    }

    private fun clearComparisonState() {
        translationCoordinator.invalidateComparisons()
        _state.update { it.copy(comparisonResults = emptyList()) }
    }

    suspend fun onShutdown() {
        // First: a normal translation or comparison request still in flight uses plugin
        // services, and application shutdown disables plugins and closes their resources
        // right after this returns.
        cancelTranslationWork()
        cancelDocumentTranslation()
        ocrOwner.close()?.previousJob?.cancel()
        dictionarySession.cancel()
        imageSession.cancel()
        if (settingsState.value.clearHistoryOnExit) {
            // A failure is recorded by the repository; shutdown must still release resources.
            historyRepository.clearHistory().fold(success = { }, failure = { })
        }
        handleTextToSpeechUseCase.shutdown()
    }

    private suspend fun updateStatusBar(
        code: StatusCode,
        type: NotificationType,
        isTemporary: Boolean
    ) {
        sendEvent(MainEvent.UpdateStatusBar(code, type, isTemporary))
    }

    private suspend fun sendEvent(event: MainEvent, publish: ((() -> Unit) -> Unit)? = null) {
        _eventChannel.send(MainEventDelivery(event, publish))
    }
}

internal fun replaceCorrectionAtRange(text: String, correction: Correction, suggestion: String): String? {
    if (correction.startIndex < 0 || correction.endIndex > text.length ||
        correction.startIndex >= correction.endIndex ||
        text.substring(correction.startIndex, correction.endIndex) != correction.original) return null
    return text.replaceRange(correction.startIndex, correction.endIndex, suggestion)
}
