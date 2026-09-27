package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.api.imagesearch.ImageResult
import com.github.ahatem.qtranslate.core.main.mvi.LookupTool
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.effectiveTranslatorCount
import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.effectiveLayoutPresetId
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputRequest
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.api.plugin.StandardOptions
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.ui.swing.main.history.TranslationHistoryBar
import com.github.ahatem.qtranslate.ui.swing.main.history.TranslationHistoryBarState
import com.github.ahatem.qtranslate.ui.swing.main.history.TranslationHistoryBarStrings
import com.github.ahatem.qtranslate.ui.swing.main.input.InputTextPanel
import com.github.ahatem.qtranslate.ui.swing.main.input.InputTextState
import com.github.ahatem.qtranslate.ui.swing.main.languagebar.LanguageSelectionBar
import com.github.ahatem.qtranslate.ui.swing.main.languagebar.LanguageSelectionBarState
import com.github.ahatem.qtranslate.ui.swing.main.languagebar.LanguageSelectionBarStrings
import com.github.ahatem.qtranslate.ui.swing.main.layout.ComponentRegistry
import com.github.ahatem.qtranslate.ui.swing.main.layout.LayoutManager
import com.github.ahatem.qtranslate.ui.swing.main.layout.WorkspaceDockHost
import com.github.ahatem.qtranslate.ui.swing.main.lookup.LookupDock
import com.github.ahatem.qtranslate.ui.swing.imagesearch.ImageSearchPanel
import com.github.ahatem.qtranslate.ui.swing.imagesearch.ImageSearchPanelState
import com.github.ahatem.qtranslate.ui.swing.imagesearch.imageSearchStrings
import com.github.ahatem.qtranslate.ui.swing.main.output.ExtraOutputPanel
import com.github.ahatem.qtranslate.ui.swing.main.output.ExtraOutputState
import com.github.ahatem.qtranslate.ui.swing.main.output.OutputTextPanel
import com.github.ahatem.qtranslate.ui.swing.main.output.NoServiceState
import com.github.ahatem.qtranslate.ui.swing.main.output.OutputTextState
import com.github.ahatem.qtranslate.ui.swing.main.output.CompareBoard
import com.github.ahatem.qtranslate.ui.swing.main.output.CompareBoardState
import com.github.ahatem.qtranslate.ui.swing.main.output.CompareEmptyState
import com.github.ahatem.qtranslate.ui.swing.main.output.ProviderPresentation
import com.github.ahatem.qtranslate.ui.swing.main.output.ProviderRole
import com.github.ahatem.qtranslate.ui.swing.main.output.ProviderStatus
import com.github.ahatem.qtranslate.ui.swing.main.output.primaryProviderStatus
import com.github.ahatem.qtranslate.ui.swing.main.output.TranslationProviderState
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonStatus
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelector
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelectorState
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorPopupButton
import com.github.ahatem.qtranslate.ui.swing.dictionary.DictionaryPanel
import com.github.ahatem.qtranslate.ui.swing.dictionary.DictionaryPanelState
import com.github.ahatem.qtranslate.ui.swing.main.statusbar.StatusBar
import com.github.ahatem.qtranslate.ui.swing.main.widgets.Action
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.copyToClipboard
import com.github.ahatem.qtranslate.ui.swing.shared.util.installContentDropHandler
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledEditorFallbackFont
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledEditorFont
import com.github.ahatem.qtranslate.ui.swing.shared.util.toImageData
import com.github.ahatem.qtranslate.ui.swing.shared.util.choices
import com.github.ahatem.qtranslate.ui.swing.shared.util.selectedIdOr
import com.github.ahatem.qtranslate.ui.swing.shared.util.withKey
import java.awt.BorderLayout
import java.awt.ComponentOrientation
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

class MainContentView(
    private val iconManager: IconManager,
    private val localizer: LocalizationManager,
    private val dispatch: (MainIntent) -> Unit,
    private val dispatchSettings: (SettingsIntent) -> Unit,
    private val onOpenSnippingTool: () -> Unit,
    /**
     * Opens the document translation dialog, with [java.io.File] when one is already chosen --
     * a document pasted into the input pane, for instance.
     */
    private val onOpenDocumentTranslation: (java.io.File?) -> Unit,
    private val onNotificationsClicked: () -> Unit,
    private val onConfigureService: (String) -> Unit,
    private val onOpenServiceSettings: () -> Unit,
    /**
     * Gives the main window enough width for the Lookup Dock before it opens, if it does not
     * already have it: growing a resizable frame within its monitor's work area, or doing nothing
     * when the frame is maximized or already wide enough.
     */
    private val onEnsureLookupDockRoom: () -> Unit,
    /** Opens the page an image came from. */
    private val onOpenImageSource: (ImageResult) -> Unit,
) : JPanel(BorderLayout(0, 0)) {

    private val translationHistoryBar: TranslationHistoryBar = TranslationHistoryBar(
        iconManager = iconManager,
        onBackward = { dispatch(MainIntent.UndoTranslation) },
        onForward = { dispatch(MainIntent.RedoTranslation) },
        onImageTranslate = { onOpenSnippingTool() },
        onDocumentTranslate = { onOpenDocumentTranslation(null) },
    )

    private val translatorSelector = TranslatorSelector(
        iconManager = iconManager,
        onServiceSelected = { type, serviceId ->
            dispatchSettings(
                SettingsIntent.UpdateServiceInActivePreset(type, serviceId)
            )
            if (type == ServiceRole.TRANSLATOR) dispatch(MainIntent.Translate())
        },
        onConfigureService = onConfigureService
    )

    /** Compact primary selector mounted inside the Comparison result header. */
    private val comparisonPrimarySelector = TranslatorPopupButton(
        iconManager = iconManager,
        onTranslatorSelected = { serviceId ->
            // Promotion, not plain selection: the old primary takes the
            // promoted translator's comparison slot, so the set never shrinks.
            dispatchSettings(SettingsIntent.PromoteTranslatorToPrimary(serviceId))
            dispatch(MainIntent.Translate())
        }
    ).apply { actionTooltip = localizer.getString("main_window.comparison_change_primary") }

    private val languageSelectionBar = LanguageSelectionBar(
        iconManager = iconManager,
        localizer = localizer,
        onClear = { dispatch(MainIntent.UpdateInputText("")) },
        onSourceLanguageSelected = { lang ->
            dispatch(MainIntent.SelectSourceLanguage(lang))
            dispatchSettings(SettingsIntent.ToggleSetting { it.copy(preferredSourceLanguage = lang.tag) })
        },
        onSwap = { dispatch(MainIntent.SwapLanguages) },
        onTargetLanguageSelected = { lang ->
            dispatch(MainIntent.SelectTargetLanguage(lang))
            dispatchSettings(SettingsIntent.ToggleSetting { it.copy(preferredTargetLanguage = lang.tag) })
        },
        onTranslate = { dispatch(MainIntent.Translate()) },
        onCancel = { dispatch(MainIntent.CancelTranslation) }
    )

    private val inputTextPanel = InputTextPanel(
        iconManager = iconManager,
        localizationManager = localizer,
        onTextChanged = { text -> dispatch(MainIntent.UpdateInputText(text)) },
        onListen = { text -> dispatch(MainIntent.ListenToText(TextSource.Input, text)) },
        onTranslateRequest = { text -> dispatch(MainIntent.Translate(text)) },
        onCorrectionApplied = { correction, suggestion ->
            dispatch(MainIntent.ApplyCorrection(correction, suggestion))
        },
        onImageDropped = { image -> dispatch(MainIntent.OcrAndTranslateImage(image.toImageData("png"))) },
        onDocumentPasted = { file -> onOpenDocumentTranslation(file) },
        onFindInDictionary = { word -> showDictionaryWithWord(word) },
        onSearchImages = { word -> showImagesForWord(word) },
    )

    private val outputTextPanel = OutputTextPanel(
        iconManager = iconManager,
        localizationManager = localizer,
        onListen = { text -> dispatch(MainIntent.ListenToText(TextSource.Output, text)) },
        onTranslateRequest = { text ->
            dispatch(MainIntent.UpdateInputText(text))
            dispatch(MainIntent.Translate(text))
        },
        onFindInDictionary = { word -> showDictionaryWithWord(word, currentTargetLanguage) },
        onSearchImages = { word -> showImagesForWord(word, currentTargetLanguage) },
        onSetAsInput = { text ->
            dispatch(MainIntent.UpdateInputText(text))
            inputTextPanel.requestFocusOnText()
        },
    )

    private val compareBoard = CompareBoard(
        primarySelector = comparisonPrimarySelector,
        iconManager = iconManager
    )

    private val extraOutputPanel = ExtraOutputPanel(
        iconManager = iconManager,
        localizationManager = localizer,
        onListen = { text -> dispatch(MainIntent.ListenToText(TextSource.ExtraOutput, text)) },
        onTranslateRequest = { text ->
            dispatch(MainIntent.UpdateInputText(text))
            dispatch(MainIntent.Translate(text))
        },
        onFindInDictionary = { word -> showDictionaryWithWord(word, currentExtraOutputLanguage) },
        onSearchImages = { word -> showImagesForWord(word, currentExtraOutputLanguage) },
        onSetAsInput = { text ->
            dispatch(MainIntent.UpdateInputText(text))
            inputTextPanel.requestFocusOnText()
        },
    )

    val statusBar: StatusBar = StatusBar(
        iconManager = iconManager,
        onNotificationsClicked = { onNotificationsClicked() },
    )

    // Resolved at render time; captured by lambdas so every lookup uses the current language.
    private var currentLookupLanguage: LanguageCode = LanguageCode.ENGLISH
    private var currentTargetLanguage: LanguageCode = LanguageCode.ENGLISH
    private var currentExtraOutputLanguage: LanguageCode = LanguageCode.ENGLISH

    private val dictionaryPanel = DictionaryPanel(
        iconManager = iconManager,
        onLookup = { word -> dispatch(MainIntent.LookupWord(word, currentLookupLanguage)) },
        onServiceSelected = { serviceId ->
            dispatchSettings(SettingsIntent.UpdateServiceInActivePreset(ServiceRole.DICTIONARY, serviceId))
            val word = lastDictionaryKey?.word ?: ""
            if (word.isNotBlank()) dispatch(MainIntent.LookupWord(word, currentLookupLanguage))
        },
    )

    private val imageSearchPanel = ImageSearchPanel()

    private val lookupDock = LookupDock(
        dictionary = dictionaryPanel,
        images = imageSearchPanel,
        iconManager = iconManager,
        onToolSelected = { tool -> dispatch(MainIntent.SelectLookupTool(tool)) },
        onClose = { dispatch(MainIntent.CloseLookupDock) },
    )

    // Separate wrapper so LayoutManager.switchLayout()'s removeAll() never touches dictionaryPanel.
    private val contentWrapper = JPanel(BorderLayout())

    private val layoutManager = LayoutManager(
        ComponentRegistry(
            historyBar = translationHistoryBar,
            translatorSelector = translatorSelector,
            languageBar = languageSelectionBar,
            inputPanel = inputTextPanel,
            outputPanel = outputTextPanel,
            compareBoard = compareBoard,
            extraOutputPanel = extraOutputPanel,
            statusBar = statusBar
        ), contentWrapper
    )

    /**
     * The whole workspace and, beside it, the lookup dock. Its own component rather than a split
     * pane: the dock has one edge to drag, one side to be on, and a width to remember, and a split
     * pane's inverted axis in a right-to-left interface made all three unreliable.
     */
    private val dockHost = WorkspaceDockHost(contentWrapper, lookupDock)

    private var lastState: Pair<MainState, SettingsState>? = null
    private var lastDictionaryKey: DictionaryKey? = null
    private var currentLayoutId: String? = null
    /** Last arranged layout, so eligibility changes re-arrange without rewriting the preference. */
    private var lastEffectiveLayoutId: String? = null
    /** The translate binding the settings ask for, so an unchanged request is not re-issued. */
    private var requestedTranslateKeyStroke: KeyStroke? = null

    /**
     * The translate binding the panes actually hold, which differs from the requested one while a
     * request is refused. Kept separately so a later change releases the right stroke.
     */
    private var installedTranslateKeyStroke: KeyStroke? = null

    private data class DictionaryKey(
        val isVisible: Boolean,
        val isLoading: Boolean,
        val entries: List<com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry>,
        val word: String,
        val hasFailed: Boolean,
        val lookupLanguage: LanguageCode,
        val selectedDictionaryId: String?,
        val dictionaryCount: Int,
        val autoSource: com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource,
        /** Part of the key so the headword's Listen control flips when playback starts or stops. */
        val isTtsPlaying: Boolean,
    )

    init {
        add(dockHost, BorderLayout.CENTER)

        // Escape is owned by MainWindowEscapeBinding on the frame's root pane
        // (cancel in-flight translation first, otherwise hide the window).
        // No binding here so the same keystroke is never registered twice in
        // the same focused window.
    }

    fun render(mainState: MainState, settingsState: SettingsState) {
        val config = settingsState.workingConfiguration
        // Effective arrangement: a requested Comparison without two usable
        // translators deterministically shows Classic. The saved preference is
        // never rewritten, so eligibility restores Comparison naturally.
        val effectiveLayoutId = config.effectiveLayoutPresetId(mainState.availableTranslatorIds)

        // Told outright rather than left to the orientation cascade, which reaches the host at a
        // point in startup that depends on when this view was added to the window.
        val direction = if (localizer.isRtl) ComponentOrientation.RIGHT_TO_LEFT else ComponentOrientation.LEFT_TO_RIGHT
        if (dockHost.componentOrientation != direction) dockHost.componentOrientation = direction

        if (lastState == null || lastState?.second?.workingConfiguration?.layoutPresetId != config.layoutPresetId || lastEffectiveLayoutId != effectiveLayoutId) {
            layoutManager.switchLayout(effectiveLayoutId, localizer.isRtl)
            currentLayoutId = effectiveLayoutId
            lastEffectiveLayoutId = effectiveLayoutId
        }
        translationHistoryBar.setStatusVisible(effectiveLayoutId != LayoutPresetIds.COMPARISON)

        if (lastState == null ||
            lastState?.second?.workingConfiguration?.toolbarVisibility != config.toolbarVisibility ||
            lastState?.second?.workingConfiguration?.extraOutputType != config.extraOutputType
        ) {
            layoutManager.updateVisibility(config)
        }

        updateTranslateKeyStroke(config)
        renderLookupDock(mainState, config)
        renderComponents(mainState, config, effectiveLayoutId)
        lastState = mainState to settingsState
    }

    /**
     * Keeps the per-pane translate keystroke in sync with the user's configured binding.
     * Binding lives on each AdvancedTextPane (WHEN_FOCUSED) so the pane can pass selected
     * text to onTranslateRequest rather than always using the full input text.
     *
     * A pane refuses a binding another command already owns, so a refused request leaves the
     * previous binding in place and is not recorded as installed. The setting itself still stands,
     * and the frame-level local hotkey still answers it outside the text panes.
     */
    private fun updateTranslateKeyStroke(config: Configuration) {
        val binding = config.hotkeys.find { it.action == HotkeyAction.TRANSLATE }
        val requested = binding?.takeIf { it.isEnabled }?.toKeyStroke()
        if (requested == requestedTranslateKeyStroke) return
        requestedTranslateKeyStroke = requested

        val previous = installedTranslateKeyStroke
        // Every pane is asked, so the binding counts as installed only if all of them took it.
        val accepted = listOf(
            inputTextPanel.setTranslateKeyStroke(previous, requested),
            outputTextPanel.setTranslateKeyStroke(previous, requested),
            extraOutputPanel.setTranslateKeyStroke(previous, requested),
        ).all { it }
        installedTranslateKeyStroke = if (accepted) requested else previous
    }

    private fun renderCompareBoard(
        mainState: MainState,
        config: Configuration,
        selectedTranslatorId: String?,
        selectedTranslator: com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo?
    ) {
        val primaryStatus = primaryProviderStatus(
            mainState.translatedText, mainState.isLoading, mainState.translationFailed
        )
        val readyCount = config.effectiveTranslatorCount(mainState.availableTranslatorIds)
        val primaryState = TranslationProviderState(
            serviceId = selectedTranslatorId ?: "",
            serviceName = selectedTranslator?.name ?: localizer.getString("main_window.no_translator"),
            iconPath = selectedTranslator?.iconPath,
            role = ProviderRole.PRIMARY,
            presentation = ProviderPresentation.MAIN,
            status = primaryStatus,
            text = mainState.translatedText,
            loadingText = localizer.getString("main_window.comparison_loading"),
            failureText = localizer.getString("main_window.comparison_failure"),
            copyLabel = localizer.getString("main_window.comparison_copy"),
            listenLabel = localizer.getString("main_window_editor_context_menu.listen"),
            stopLabel = localizer.getString("common.stop"),
            isTtsPlaying = mainState.isTtsPlaying,
            primaryLabel = localizer.getString("main_window.comparison_primary"),
            definition = mainState.inlineDefinition,
            findInDictionaryLabel = localizer.getString("main_window_editor_context_menu.find_in_dictionary"),
            searchImagesLabel = localizer.getString("main_window_editor_context_menu.search_images"),
            setAsInputLabel = localizer.getString("main_window_editor_context_menu.set_as_input"),
            fontConfig = config.scaledEditorFont,
            fallbackFontConfig = config.scaledEditorFallbackFont,
            selectorState = TranslatorSelectorState(
                availableTranslators = mainState.getAvailableServicesFor(ServiceRole.TRANSLATOR),
                selectedTranslatorId = selectedTranslatorId,
                isLoading = mainState.isLoading
            ),
            onCopy = { text -> text.copyToClipboard(); dispatch(MainIntent.NotifyTextCopied) },
            onListen = { dispatch(MainIntent.ListenToText(textSource = TextSource.Output)) },
            onStop = { dispatch(MainIntent.StopTTS) },
            onTranslateRequest = { text ->
                dispatch(MainIntent.UpdateInputText(text))
                dispatch(MainIntent.Translate(text))
            },
            onFindInDictionary = { word -> showDictionaryWithWord(word, currentTargetLanguage) },
            onSearchImages = { word -> showImagesForWord(word, currentTargetLanguage) },
            onSetAsInput = { text ->
                dispatch(MainIntent.UpdateInputText(text))
                inputTextPanel.requestFocusOnText()
            },
            getContextMenuLabel = { key ->
                localizer.getString("main_window_editor_context_menu.$key")
            }
        )
        val providerInfos = mainState.availableServices.associateBy { it.id }
        val secondaries = mainState.comparisonResults.map { result ->
            val info = providerInfos[result.serviceId]
            TranslationProviderState(
                serviceId = result.serviceId,
                serviceName = info?.name ?: result.serviceName
                ?: localizer.getString("main_window.comparison_unavailable"),
                iconPath = info?.iconPath,
                role = ProviderRole.SECONDARY,
                presentation = ProviderPresentation.MAIN,
                status = when (result.status) {
                    ComparisonStatus.LOADING -> ProviderStatus.LOADING
                    ComparisonStatus.SUCCESS -> ProviderStatus.SUCCESS
                    ComparisonStatus.FAILURE -> ProviderStatus.FAILURE
                },
                text = result.text,
                errorMessage = result.errorMessage,
                loadingText = localizer.getString("main_window.comparison_loading"),
                failureText = localizer.getString("main_window.comparison_failure"),
                copyLabel = localizer.getString("main_window.comparison_copy"),
                detailsLabel = localizer.getString("main_window.comparison_details"),
                fontConfig = config.scaledEditorFont,
                fallbackFontConfig = config.scaledEditorFallbackFont,
                onCopy = { text -> text.copyToClipboard(); dispatch(MainIntent.NotifyTextCopied) }
            )
        }
        compareBoard.render(
            CompareBoardState(
                primary = primaryState,
                secondaries = secondaries,
                emptyState = CompareEmptyState(
                    title = localizer.getString("main_window.comparison_empty"),
                    message = localizer.getString("main_window.comparison_empty_subtitle", readyCount)
                )
            )
        )
    }

    /**
     * Keeps the lookup dock in step with the state: whether it is open, which tool it shows, and
     * what that tool shows.
     *
     * Open is a request, not a promise of room: the host presents the dock only while the window is
     * wide enough for it and the workspace, and otherwise leaves the workspace whole.
     */
    private fun renderLookupDock(mainState: MainState, config: Configuration) {
        dockHost.isDockVisible = mainState.isLookupDockOpen
        lookupDock.showTool(mainState.lookupDockTool)
        lookupDock.setLabels(
            dictionary = localizer.getString("dictionary_dialog.title"),
            images = localizer.getString("image_search_dialog.title"),
            close = localizer.getString("common.close")
        )

        renderDictionaryPanel(mainState, config)
        if (mainState.isImagesDockVisible) renderImageSearchPanel(mainState)
    }

    private fun renderImageSearchPanel(mainState: MainState) {
        val language = mainState.resolvedSourceLanguage
        imageSearchPanel.render(
            ImageSearchPanelState(
                isLoading = mainState.isImageSearchLoading,
                results = mainState.imageResults,
                searchedTerm = mainState.imageSearchTerm,
                hasFailed = mainState.imageSearchFailed,
                strings = imageSearchStrings(localizer, mainState.imageSearchTerm),
                onSearch = { term -> dispatch(MainIntent.SearchImages(term, language)) },
                onImageOpened = onOpenImageSource
            )
        )
    }

    private fun renderDictionaryPanel(mainState: MainState, config: Configuration) {
        val resolvedLang = mainState.resolvedSourceLanguage
        currentLookupLanguage = resolvedLang

        val availableDicts = mainState.getAvailableServicesFor(
            com.github.ahatem.qtranslate.api.plugin.ServiceRole.DICTIONARY
        )
        val selectedDictId = lastState?.second?.workingConfiguration
            ?.getActivePreset()?.selectedServices
            ?.get(com.github.ahatem.qtranslate.api.plugin.ServiceRole.DICTIONARY)

        val key = DictionaryKey(
            isVisible         = mainState.isDictionaryPanelVisible,
            isLoading         = mainState.isDictionaryLoading,
            entries           = mainState.dictionaryEntries,
            word              = mainState.dictionaryWord,
            hasFailed         = mainState.dictionaryFailed,
            lookupLanguage    = resolvedLang,
            selectedDictionaryId = selectedDictId,
            dictionaryCount   = availableDicts.size,
            autoSource        = config.dictionaryAutoSource,
            isTtsPlaying      = mainState.isTtsPlaying,
        )
        if (key == lastDictionaryKey) return
        lastDictionaryKey = key

        if (key.isVisible) {
            dictionaryPanel.render(
                DictionaryPanelState(
                    lookupButtonLabel     = localizer.getString("dictionary_dialog.lookup_button"),
                    hintMessage           = localizer.getString("dictionary_dialog.hint_message"),
                    notFoundMessage       = localizer.getString("dictionary_dialog.not_found_message", key.word),
                    loadingMessage        = localizer.getString("dictionary_dialog.loading_message"),
                    errorMessage          = localizer.getString("dictionary_dialog.error_message"),
                    synonymsLabel         = localizer.getString("dictionary_dialog.synonyms_label"),
                    listenTooltip         = localizer.getString("common.listen"),
                    stopListeningTooltip  = localizer.getString("common.stop"),
                    isLoading             = key.isLoading,
                    isTtsPlaying          = key.isTtsPlaying,
                    entries               = key.entries,
                    lookedUpWord          = key.word,
                    hasFailed             = key.hasFailed,
                    availableDictionaries = availableDicts,
                    selectedDictionaryId  = key.selectedDictionaryId,
                    autoSource            = key.autoSource,
                    autoSourceOffLabel        = localizer.getString("dictionary_dialog.auto_source_off"),
                    autoSourceTranslatedLabel = localizer.getString("dictionary_dialog.auto_source_translated"),
                    autoSourceSourceLabel     = localizer.getString("dictionary_dialog.auto_source_source"),
                    onAutoSourceChanged   = { newSource ->
                        dispatchSettings(
                            SettingsIntent.ToggleSetting { it.copy(dictionaryAutoSource = newSource) }
                        )
                    },
                    // The headword belongs to the lookup, not to a panel, so it carries the
                    // language the lookup was made in rather than the input panel's.
                    onListen = { word ->
                        dispatch(
                            MainIntent.ListenToText(
                                textSource = TextSource.Input,
                                text = word,
                                language = key.lookupLanguage
                            )
                        )
                    },
                    onStopListening = { dispatch(MainIntent.StopTTS) },
                )
            )
        }
    }

    private fun renderComponents(mainState: MainState, config: Configuration, effectiveLayoutId: String) {
        currentTargetLanguage = mainState.targetLanguage

        // BackwardTranslate output is in the source language; all other extra output types are in target.
        currentExtraOutputLanguage = if (config.extraOutputType == ExtraOutputType.BackwardTranslate) {
            currentLookupLanguage
        } else {
            mainState.targetLanguage
        }

        val allLanguages = mainState.availableLanguages

        val activePreset = config.getActivePreset()
        val selectedTranslatorId = activePreset?.selectedServices?.get(ServiceRole.TRANSLATOR)
        val selectedTranslator = mainState.availableServices.find { it.id == selectedTranslatorId }

        val statusText = localizer.getString(
            "main_window.status_format",
            selectedTranslator?.name ?: localizer.getString("main_window.no_translator"),
            mainState.sourceLanguage.getDisplayName(autoDetectLabel = localizer.getString("common.auto_detect")),
            mainState.targetLanguage.getDisplayName(autoDetectLabel = localizer.getString("common.auto_detect"))
        )

        translationHistoryBar.render(
            state = TranslationHistoryBarState(
                statusText = statusText,
                canGoBackward = mainState.canUndo,
                canGoForward = mainState.canRedo,
                isLoading = mainState.isLoading,
                strings = TranslationHistoryBarStrings(
                    backwardTooltip = localizer.getString("main_window_history_bar.backward_tooltip"),
                    forwardTooltip = localizer.getString("main_window_history_bar.forward_tooltip"),
                    imageTranslateTooltip = localizer.getString("main_window_history_bar.image_translate_tooltip"),
                    documentTranslateTooltip = localizer.getString("main_window_history_bar.document_translate_tooltip"),
                ),
            )
        )

        translatorSelector.render(
            TranslatorSelectorState(
                availableTranslators = mainState.getAvailableServicesFor(ServiceRole.TRANSLATOR),
                selectedTranslatorId = selectedTranslatorId,
                isLoading = mainState.isLoading,
                availableServices = mainState.availableServices,
                selectedServices = activePreset?.selectedServices.orEmpty(),
                style = config.serviceSelectorStyle,
                appearance = config.serviceSelectorAppearance
            )
        )

        languageSelectionBar.render(
            LanguageSelectionBarState(
                isLoading = mainState.isLoading,
                canClear = mainState.inputText.isNotBlank(),
                canSwap = mainState.translatedText.isNotBlank(),
                allSourceLanguages = allLanguages,
                allTargetLanguages = allLanguages - setOf(LanguageCode.AUTO),
                selectedSourceLanguage = mainState.sourceLanguage,
                detectedSourceLanguage = mainState.detectedSourceLanguage,
                selectedTargetLanguage = mainState.targetLanguage,
                strings = LanguageSelectionBarStrings(
                    translateButtonText = localizer.getString("main_window_language_bar.translate_button"),
                    cancelButtonText    = localizer.getString("main_window_language_bar.cancel_button"),
                    clearTooltip        = localizer.getString("main_window_language_bar.clear_tooltip"),
                    swapTooltip         = localizer.getString("main_window_language_bar.swap_languages_tooltip")
                )
            )
        )

        val hasInputText = mainState.inputText.isNotBlank()
        val isTtsPlaying = mainState.isTtsPlaying
        val listenStopTooltip = if (isTtsPlaying)
            localizer.getString("common.stop") else localizer.getString("main_window_editor_context_menu.listen")
        val listenStopIcon = if (isTtsPlaying) Icons.CLOSE else Icons.SPEAK
        val inputActionsState = TextActionsState(
            actions = listOf(
                Action(
                    id = "copy_input",
                    iconPath = Icons.COPY,
                    tooltip = localizer.getString("main_window_editor_context_menu.copy"),
                    isEnabled = hasInputText && !mainState.isLoading,
                    isVisible = true,
                    onClick = { mainState.inputText.copyToClipboard(); dispatch(MainIntent.NotifyTextCopied) }
                ),
                Action(
                    id = if (isTtsPlaying) "stop_tts_input" else "listen_input",
                    iconPath = listenStopIcon,
                    tooltip = listenStopTooltip,
                    isEnabled = if (isTtsPlaying) true else hasInputText && !mainState.isLoading,
                    isVisible = true,
                    onClick = {
                        if (isTtsPlaying) dispatch(MainIntent.StopTTS)
                        else dispatch(MainIntent.ListenToText(textSource = TextSource.Input))
                    }
                ),
            )
        )

        inputTextPanel.render(
            InputTextState(
                text = mainState.inputText,
                corrections = mainState.spellCheckCorrections,
                fontConfig = config.scaledEditorFont,
                fallbackFontConfig = config.scaledEditorFallbackFont,
                isEditable = true,
                isLoading = mainState.isLoading,
                actionsState = inputActionsState
            )
        )

        val hasOutputText = mainState.translatedText.isNotBlank()
        val hasExtraText = mainState.extraOutputText.isNotBlank()

        // Nothing can be translated without a translator, and an empty window gives a new
        // user no clue why. Point them at the setting that fixes it.
        val noService = if (mainState.getAvailableServicesFor(ServiceRole.TRANSLATOR).isEmpty()) {
            NoServiceState(
                message = localizer.getString("main_window.no_service_message"),
                actionLabel = localizer.getString("main_window.no_service_action"),
                onAction = onOpenServiceSettings
            )
        } else null

        outputTextPanel.render(
            OutputTextState(
                text = mainState.translatedText,
                // Shown automatically for a single word, empty otherwise, so a multi-word
                // translation lays out exactly as it did before this existed.
                definition = mainState.inlineDefinition,
                noService = noService,
                isLoading = mainState.isLoading,
                fontConfig = config.scaledEditorFont,
                fallbackFontConfig = config.scaledEditorFallbackFont,
                actionsState = TextActionsState(
                    listOf(
                        Action(
                            id = "copy_output",
                            iconPath = Icons.COPY,
                            tooltip = localizer.getString("main_window_editor_context_menu.copy"),
                            isEnabled = hasOutputText && !mainState.isLoading,
                            isVisible = true,
                            onClick = { mainState.translatedText.copyToClipboard(); dispatch(MainIntent.NotifyTextCopied) }
                        ),
                        Action(
                            id = if (isTtsPlaying) "stop_tts_output" else "listen_output",
                            iconPath = listenStopIcon,
                            tooltip = listenStopTooltip,
                            isEnabled = if (isTtsPlaying) true else hasOutputText && !mainState.isLoading,
                            isVisible = true,
                            onClick = {
                                if (isTtsPlaying) dispatch(MainIntent.StopTTS)
                                else dispatch(MainIntent.ListenToText(textSource = TextSource.Output))
                            }
                        )
                    )
                )
            )
        )
        comparisonPrimarySelector.render(
            TranslatorSelectorState(
                availableTranslators = mainState.getAvailableServicesFor(ServiceRole.TRANSLATOR),
                selectedTranslatorId = selectedTranslatorId,
                isLoading = mainState.isLoading
            )
        )

        // The board backing the Comparison layout renders only there; other
        // layouts keep using the classic output panel above.
        if (effectiveLayoutId == LayoutPresetIds.COMPARISON) {
            renderCompareBoard(mainState, config, selectedTranslatorId, selectedTranslator)
        }

        // The extra-output pane offers whatever the service behind the active type declares.
        // Backward translation has no options, and neither does a service that declares none —
        // both come out as an empty list, which hides the configure button.
        val extraOutputOption = when (config.extraOutputType) {
            ExtraOutputType.Summarize ->
                mainState.serviceOptions[ServiceRole.SUMMARIZER]?.withKey(StandardOptions.KEY_SUMMARY_LENGTH)
            ExtraOutputType.Rewrite ->
                mainState.serviceOptions[ServiceRole.REWRITER]?.withKey(StandardOptions.KEY_REWRITE_STYLE)
            else -> null
        }
        val extraOutputSelection = when (config.extraOutputType) {
            ExtraOutputType.Summarize -> config.summaryLength
            ExtraOutputType.Rewrite -> config.rewriteStyle
            else -> ""
        }

        extraOutputPanel.render(
            ExtraOutputState(
                text = mainState.extraOutputText,
                isVisible = config.extraOutputType != ExtraOutputType.None,
                // Stays loading after the main translation has landed — this panel is fed by
                // its own request and must not make the main output wait for it.
                isLoading = mainState.isLoading || mainState.isExtraOutputLoading,
                fontConfig = config.scaledEditorFont,
                fallbackFontConfig = config.scaledEditorFallbackFont,
                activeType = config.extraOutputType,
                // Every extra output is derived as part of a translation, so until one has
                // landed there is nothing for this panel to show.
                placeholderText = localizer.getString("extra_output.placeholder")
                    .takeIf { mainState.translatedText.isBlank() },

                labelBackward = localizer.getString("extra_output.label_backward"),
                labelSummary = localizer.getString("extra_output.label_summary"),
                labelRewrite = localizer.getString("extra_output.label_rewrite"),

                labelConfigure = localizer.getString("common.configure"),

                optionChoices = extraOutputOption?.choices(localizer).orEmpty(),
                selectedOptionId = extraOutputOption?.selectedIdOr(extraOutputSelection),

                onTypeChanged = { type ->
                    val updated = config.copy(extraOutputType = type)
                    dispatchSettings(
                        SettingsIntent.UpdateDraft(
                            updated
                        )
                    )
                    // Only this panel changed. The translation beside it is still correct, so
                    // asking for a new one would discard what the user is reading and pay for
                    // the same text twice.
                    dispatch(MainIntent.RefreshExtraOutput(ExtraOutputRequest.from(updated)))
                },
                onOptionSelected = { id ->
                    // Which setting the id belongs to follows from the active type; the panel
                    // itself never learns that, so a new option kind only touches this branch.
                    val updated = when (config.extraOutputType) {
                        ExtraOutputType.Summarize -> config.copy(summaryLength = id)
                        ExtraOutputType.Rewrite -> config.copy(rewriteStyle = id)
                        else -> config
                    }
                    dispatchSettings(SettingsIntent.UpdateDraft(updated))
                    dispatch(MainIntent.RefreshExtraOutput(ExtraOutputRequest.from(updated)))
                },

                actionsState = TextActionsState(
                    listOf(
                        Action(
                            id = "copy_extra",
                            iconPath = Icons.COPY,
                            tooltip = localizer.getString("main_window_editor_context_menu.copy"),
                            isEnabled = hasExtraText && !mainState.isLoading,
                            isVisible = true,
                            onClick = { mainState.extraOutputText.copyToClipboard(); dispatch(MainIntent.NotifyTextCopied) }
                        ),
                        Action(
                            id = if (isTtsPlaying) "stop_tts_extra" else "listen_extra",
                            iconPath = listenStopIcon,
                            tooltip = listenStopTooltip,
                            isEnabled = if (isTtsPlaying) true else hasExtraText && !mainState.isLoading,
                            isVisible = true,
                            onClick = {
                                if (isTtsPlaying) dispatch(MainIntent.StopTTS)
                                else dispatch(MainIntent.ListenToText(textSource = TextSource.ExtraOutput))
                            }
                        )
                    )
                )
            )
        )
    }

    /**
     * Moves keyboard focus into the input editor. Targets the text pane itself:
     * the panel is only its container, and focus landing there leaves typing
     * going nowhere.
     */
    fun requestFocusOnInput() {
        inputTextPanel.requestFocusOnText()
    }

    /** Moves focus into the input text pane. Used by the FOCUS_INPUT local hotkey. */
    fun focusInput() {
        inputTextPanel.requestFocusOnText()
    }

    /** Moves focus into the visible translation pane: the Primary result in Comparison. Used by the FOCUS_OUTPUT local hotkey. */
    fun focusOutput() {
        if (currentLayoutId == LayoutPresetIds.COMPARISON) {
            compareBoard.primaryProviderView.requestFocusOnText()
        } else {
            outputTextPanel.requestFocusOnText()
        }
    }

    /** Moves focus into the extra output pane. Used by the FOCUS_EXTRA_OUTPUT local hotkey. */
    fun focusExtraOutput() {
        extraOutputPanel.requestFocusOnText()
    }

    /**
     * Returns the text-pane components in focus-traversal order: Input → Output → Extra.
     * Extra is included only when its panel is currently visible (i.e. an extra output type is active).
     * Used by the frame-level [TextPaneCycleFocusPolicy] to build the Tab/Shift+Tab cycle.
     */
    fun orderedTextPanes(): List<JComponent> = buildList {
        add(inputTextPanel.textPaneComponent)
        if (currentLayoutId == LayoutPresetIds.COMPARISON) {
            add(compareBoard.primaryProviderView.textPaneComponent)
        } else {
            add(outputTextPanel.textPaneComponent)
        }
        if (extraOutputPanel.isVisible) add(extraOutputPanel.textPaneComponent)
    }

    fun setDictionarySearchWord(word: String) {
        dictionaryPanel.setSearchWord(word)
    }

    /**
     * Gives every text pane the same drop handling as the window around them.
     *
     * Needed because Swing consults only the deepest component under the pointer: a handler on the
     * frame alone never sees a drop that lands on an editor, and the editor refuses it. Called by
     * the frame, which owns the overlay and the intents these drops turn into.
     */
    fun installDropHandling(
        onContent: (com.github.ahatem.qtranslate.ui.swing.shared.util.DroppedContent) -> Unit,
        onDragOver: () -> Unit,
        onDropped: () -> Unit
    ) {
        listOf(
            inputTextPanel.textPaneComponent,
            outputTextPanel.textPaneComponent,
            compareBoard.primaryProviderView.textPaneComponent,
            extraOutputPanel.textPaneComponent
        ).forEach { it.installContentDropHandler(onContent, onDragOver, onDropped) }
    }

    /** The width, workspace plus dock, the frame should try to have before the dock opens. */
    fun comfortableWidthWithDock(): Int = dockHost.comfortableWidth()

    /**
     * Shows the pictures for [word] in the Lookup Dock.
     *
     * A main-window action always docks: the result belongs in the workspace it was asked from,
     * not in a popup whose appearance would depend on how wide the window happened to be. Making
     * room for it, when the window does not already have enough, is [onEnsureLookupDockRoom]'s job.
     */
    fun openImages(word: String, language: LanguageCode = currentLookupLanguage) {
        onEnsureLookupDockRoom()
        dispatch(MainIntent.OpenLookupDock(LookupTool.IMAGES))
        if (word.isNotBlank()) dispatch(MainIntent.SearchImages(word, language))
        // Once the tab is on screen; asking earlier finds the field not yet showing.
        javax.swing.SwingUtilities.invokeLater { imageSearchPanel.focusSearchField() }
    }

    /** Shows the dictionary for [word] in the Lookup Dock. See [openImages] for why this always docks. */
    fun openDictionary(word: String, language: LanguageCode = currentLookupLanguage) {
        onEnsureLookupDockRoom()
        dictionaryPanel.setSearchWord(word)
        dispatch(MainIntent.OpenLookupDock(LookupTool.DICTIONARY))
        if (word.isNotBlank()) dispatch(MainIntent.LookupWord(word, language))
    }

    /** The main menu's Dictionary entry: opens the dictionary, or closes it when it is what the dock shows. */
    fun toggleDictionary(initialWord: String) {
        if (lastState?.first?.isDictionaryPanelVisible == true) {
            dispatch(MainIntent.CloseLookupDock)
        } else {
            openDictionary(initialWord)
        }
    }

    private fun showImagesForWord(word: String, language: LanguageCode = currentLookupLanguage) =
        openImages(word, language)

    private fun showDictionaryWithWord(word: String, language: LanguageCode = currentLookupLanguage) =
        openDictionary(word, language)
}
