package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
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
import com.github.ahatem.qtranslate.ui.swing.main.output.ExtraOutputPanel
import com.github.ahatem.qtranslate.ui.swing.main.output.ExtraOutputState
import com.github.ahatem.qtranslate.ui.swing.main.output.OutputTextPanel
import com.github.ahatem.qtranslate.ui.swing.main.output.OutputTextState
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelector
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelectorState
import com.github.ahatem.qtranslate.ui.swing.dictionary.DictionaryPanel
import com.github.ahatem.qtranslate.ui.swing.dictionary.DictionaryPanelState
import com.github.ahatem.qtranslate.ui.swing.main.statusbar.StatusBar
import com.github.ahatem.qtranslate.ui.swing.main.widgets.Action
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.copyToClipboard
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledEditorFallbackFont
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledEditorFont
import com.github.ahatem.qtranslate.ui.swing.shared.util.toImageData
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.UIManager

class MainContentView(
    private val iconManager: IconManager,
    private val localizer: LocalizationManager,
    private val dispatch: (MainIntent) -> Unit,
    private val dispatchSettings: (SettingsIntent) -> Unit,
    private val onOpenSnippingTool: () -> Unit,
    private val onNotificationsClicked: () -> Unit,
) : JPanel(BorderLayout(0, 0)) {

    private val translationHistoryBar: TranslationHistoryBar = TranslationHistoryBar(
        iconManager = iconManager,
        onBackward = { dispatch(MainIntent.UndoTranslation) },
        onForward = { dispatch(MainIntent.RedoTranslation) },
        onImageTranslate = { onOpenSnippingTool() },
    )

    private val translatorSelector = TranslatorSelector(
        iconManager = iconManager,
        onTranslatorSelected = { serviceId ->
            dispatchSettings(
                SettingsIntent.UpdateServiceInActivePreset(ServiceType.TRANSLATOR, serviceId)
            )
            dispatch(MainIntent.Translate())
        }
    )

    private val languageSelectionBar = LanguageSelectionBar(
        iconManager = iconManager,
        localizer = localizer,
        onClear = { dispatch(MainIntent.UpdateInputText("")) },
        onSourceLanguageSelected = { lang -> dispatch(MainIntent.SelectSourceLanguage(lang)) },
        onSwap = { dispatch(MainIntent.SwapLanguages) },
        onTargetLanguageSelected = { lang -> dispatch(MainIntent.SelectTargetLanguage(lang)) },
        onTranslate = { dispatch(MainIntent.Translate()) }
    )

    private val inputTextPanel = InputTextPanel(
        iconManager = iconManager,
        localizationManager = localizer,
        onTextChanged = { text -> dispatch(MainIntent.UpdateInputText(text)) },
        onListen = { text -> dispatch(MainIntent.ListenToText(TextSource.Input, text)) },
        onTranslateRequest = { text -> dispatch(MainIntent.Translate(text)) },
        onCorrectionApplied = { original, suggestion ->
            dispatch(MainIntent.ApplyCorrection(original, suggestion))
        },
        onImageDropped = { image -> dispatch(MainIntent.OcrAndTranslateImage(image.toImageData("png"))) },
        onFindInDictionary = { word -> showDictionaryWithWord(word) },
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
    )

    val statusBar: StatusBar = StatusBar(
        iconManager = iconManager,
        onNotificationsClicked = { onNotificationsClicked() },
    )

    // Resolved at render time; captured by lambdas so every lookup uses the current language.
    private var currentLookupLanguage: LanguageCode = LanguageCode("en")
    private var currentTargetLanguage: LanguageCode = LanguageCode("en")
    private var currentExtraOutputLanguage: LanguageCode = LanguageCode("en")

    private val dictionaryPanel = DictionaryPanel(
        iconManager = iconManager,
        onLookup = { word -> dispatch(MainIntent.LookupWord(word, currentLookupLanguage)) },
        onServiceSelected = { serviceId ->
            dispatchSettings(SettingsIntent.UpdateServiceInActivePreset(ServiceType.DICTIONARY, serviceId))
            val word = lastDictionaryKey?.word ?: ""
            if (word.isNotBlank()) dispatch(MainIntent.LookupWord(word, currentLookupLanguage))
        },
        onClose  = { dispatch(MainIntent.ToggleDictionaryPanel) },
    ).apply {
        minimumSize = Dimension(220, 0)
    }

    // Separate wrapper so LayoutManager.switchLayout()'s removeAll() never touches dictionaryPanel.
    private val contentWrapper = JPanel(BorderLayout())

    private val layoutManager = LayoutManager(
        ComponentRegistry(
            historyBar = translationHistoryBar,
            translatorSelector = translatorSelector,
            languageBar = languageSelectionBar,
            inputPanel = inputTextPanel,
            outputPanel = outputTextPanel,
            extraOutputPanel = extraOutputPanel,
            statusBar = statusBar
        ), contentWrapper
    )

    private val splitPane = javax.swing.JSplitPane(
        javax.swing.JSplitPane.HORIZONTAL_SPLIT, true, contentWrapper, dictionaryPanel
    ).apply {
        resizeWeight = 1.0   // main content gets all extra space when window is resized
        dividerSize  = 0     // collapsed until panel is first shown
        border       = null
        dictionaryPanel.isVisible = false
    }

    private var savedDividerLocation: Int = -1

    private var lastState: Pair<MainState, SettingsState>? = null
    private var lastDictionaryKey: DictionaryKey? = null

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
    )

    init {
        add(splitPane, BorderLayout.CENTER)
    }

    fun render(mainState: MainState, settingsState: SettingsState) {
        val config = settingsState.workingConfiguration

        if (lastState == null || lastState?.second?.workingConfiguration?.layoutPresetId != config.layoutPresetId) {
            layoutManager.switchLayout(config.layoutPresetId, localizer.isRtl)
        }

        if (lastState == null ||
            lastState?.second?.workingConfiguration?.toolbarVisibility != config.toolbarVisibility ||
            lastState?.second?.workingConfiguration?.extraOutputType != config.extraOutputType
        ) {
            layoutManager.updateVisibility(config)
        }

        renderDictionaryPanel(mainState, config)
        renderComponents(mainState, config)
        lastState = mainState to settingsState
    }

    private fun renderDictionaryPanel(mainState: MainState, config: Configuration) {
        // Resolve source language — never pass AUTO to the dictionary API.
        val resolvedLang = when {
            mainState.sourceLanguage != LanguageCode.AUTO -> mainState.sourceLanguage
            mainState.detectedSourceLanguage != null      -> mainState.detectedSourceLanguage!!
            else                                          -> LanguageCode("en")
        }
        currentLookupLanguage = resolvedLang

        val availableDicts = mainState.getAvailableServicesFor(
            com.github.ahatem.qtranslate.core.shared.arch.ServiceType.DICTIONARY
        )
        val selectedDictId = lastState?.second?.workingConfiguration
            ?.getActivePreset()?.selectedServices
            ?.get(com.github.ahatem.qtranslate.core.shared.arch.ServiceType.DICTIONARY)

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
        )
        if (key == lastDictionaryKey) return
        lastDictionaryKey = key

        if (dictionaryPanel.isVisible != key.isVisible) {
            if (key.isVisible) {
                dictionaryPanel.isVisible = true
                val dividerPx = UIManager.getInt("SplitPane.dividerSize").coerceAtLeast(4)
                splitPane.dividerSize = dividerPx
                val loc = if (savedDividerLocation > 0) savedDividerLocation else -1
                if (loc > 0 && loc < splitPane.width - dictionaryPanel.minimumSize.width) {
                    splitPane.dividerLocation = loc
                } else {
                    // setDividerLocation(double) requires the pane to have a real pixel width.
                    // Defer via invokeLater so it fires after the layout pass — otherwise
                    // splitPane.width is still 0 and the panel opens with the wrong size.
                    javax.swing.SwingUtilities.invokeLater {
                        splitPane.setDividerLocation(0.65)
                    }
                }
            } else {
                savedDividerLocation = splitPane.dividerLocation
                dictionaryPanel.isVisible = false
                splitPane.dividerSize = 0
            }
            revalidate()
            repaint()
        }
        if (key.isVisible) {
            dictionaryPanel.render(
                DictionaryPanelState(
                    title                 = localizer.getString("dictionary_dialog.title"),
                    lookupButtonLabel     = localizer.getString("dictionary_dialog.lookup_button"),
                    closeLabel            = localizer.getString("common.close"),
                    hintMessage           = localizer.getString("dictionary_dialog.hint_message"),
                    notFoundMessage       = localizer.getString("dictionary_dialog.not_found_message", key.word),
                    loadingMessage        = localizer.getString("dictionary_dialog.loading_message"),
                    errorMessage          = localizer.getString("dictionary_dialog.error_message"),
                    synonymsLabel         = localizer.getString("dictionary_dialog.synonyms_label"),
                    isLoading             = key.isLoading,
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
                )
            )
        }
    }

    private fun renderComponents(mainState: MainState, config: Configuration) {
        currentTargetLanguage = mainState.targetLanguage

        // BackwardTranslate output is in the source language; all other extra output types are in target.
        currentExtraOutputLanguage = if (config.extraOutputType == ExtraOutputType.BackwardTranslate) {
            currentLookupLanguage
        } else {
            mainState.targetLanguage
        }

        val allLanguages = mainState.availableLanguages

        val activePreset = config.getActivePreset()
        val selectedTranslatorId = activePreset?.selectedServices?.get(ServiceType.TRANSLATOR)
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
                ),
            )
        )

        translatorSelector.render(
            TranslatorSelectorState(
                availableTranslators = mainState.getAvailableServicesFor(ServiceType.TRANSLATOR),
                selectedTranslatorId = selectedTranslatorId,
                isLoading = mainState.isLoading
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
                    clearTooltip = localizer.getString("main_window_language_bar.clear_tooltip"),
                    swapTooltip = localizer.getString("main_window_language_bar.swap_languages_tooltip")
                )
            )
        )

        val hasInputText = mainState.inputText.isNotBlank()
        val inputActionsState = TextActionsState(
            actions = listOf(
                Action(
                    id = "copy_input",
                    iconPath = "icons/lucide/copy-text.svg",
                    tooltip = localizer.getString("main_window_editor_context_menu.copy"),
                    isEnabled = hasInputText && !mainState.isLoading,
                    isVisible = true,
                    onClick = { mainState.inputText.copyToClipboard() }
                ),
                Action(
                    id = "listen_input",
                    iconPath = "icons/lucide/volume.svg",
                    tooltip = localizer.getString("main_window_editor_context_menu.listen"),
                    isEnabled = hasInputText && !mainState.isLoading,
                    isVisible = true,
                    onClick = { dispatch(MainIntent.ListenToText(textSource = TextSource.Input)) }
                ),
            )
        )

        inputTextPanel.render(
            InputTextState(
                text = mainState.inputText,
                corrections = mainState.spellCheckCorrections,
                fontConfig = config.scaledEditorFont,
                fallbackFontConfig = config.scaledEditorFallbackFont,
                isEditable = !mainState.isLoading,
                isLoading = mainState.isLoading,
                actionsState = inputActionsState
            )
        )

        val hasOutputText = mainState.translatedText.isNotBlank()
        val hasExtraText = mainState.extraOutputText.isNotBlank()

        outputTextPanel.render(
            OutputTextState(
                text = mainState.translatedText,
                isLoading = mainState.isLoading,
                fontConfig = config.scaledEditorFont,
                fallbackFontConfig = config.scaledEditorFallbackFont,
                actionsState = TextActionsState(
                    listOf(
                        Action(
                            id = "copy_output",
                            iconPath = "icons/lucide/copy-text.svg",
                            tooltip = localizer.getString("main_window_editor_context_menu.copy"),
                            isEnabled = hasOutputText && !mainState.isLoading,
                            isVisible = true,
                            onClick = { mainState.translatedText.copyToClipboard() }
                        ),
                        Action(
                            id = "listen_output",
                            iconPath = "icons/lucide/volume.svg",
                            tooltip = localizer.getString("main_window_editor_context_menu.listen"),
                            isEnabled = hasOutputText && !mainState.isLoading,
                            isVisible = true,
                            onClick = { dispatch(MainIntent.ListenToText(textSource = TextSource.Output)) }
                        )
                    )
                )
            )
        )

        extraOutputPanel.render(
            ExtraOutputState(
                text = mainState.extraOutputText,
                isVisible = config.extraOutputType != ExtraOutputType.None,
                isLoading = mainState.isLoading,
                fontConfig = config.scaledEditorFont,
                fallbackFontConfig = config.scaledEditorFallbackFont,
                activeType = config.extraOutputType,
                summaryLength = config.summaryLength,
                rewriteStyle = config.rewriteStyle,

                labelBackward = localizer.getString("extra_output.label_backward"),
                labelSummary = localizer.getString("extra_output.label_summary"),
                labelRewrite = localizer.getString("extra_output.label_rewrite"),

                labelConfigure = localizer.getString("common.configure"),

                summaryLengthLabels = listOf(
                    localizer.getString("settings_translation.summary_length_short"),
                    localizer.getString("settings_translation.summary_length_medium"),
                    localizer.getString("settings_translation.summary_length_long")
                ),
                rewriteStyleLabels = listOf(
                    localizer.getString("settings_translation.rewrite_style_formal"),
                    localizer.getString("settings_translation.rewrite_style_casual"),
                    localizer.getString("settings_translation.rewrite_style_concise"),
                    localizer.getString("settings_translation.rewrite_style_detailed"),
                    localizer.getString("settings_translation.rewrite_style_simplified")
                ),

                onTypeChanged = { type ->
                    dispatchSettings(
                        SettingsIntent.UpdateDraft(
                            config.copy(extraOutputType = type)
                        )
                    )
                    dispatch(MainIntent.Translate())
                },
                onSummaryLengthChanged = { length ->
                    dispatchSettings(
                        SettingsIntent.UpdateDraft(
                            config.copy(summaryLength = length)
                        )
                    )
                    dispatch(MainIntent.Translate())
                },
                onRewriteStyleChanged = { style ->
                    dispatchSettings(
                        SettingsIntent.UpdateDraft(
                            config.copy(rewriteStyle = style)
                        )
                    )
                    dispatch(MainIntent.Translate())
                },

                actionsState = TextActionsState(
                    listOf(
                        Action(
                            id = "copy_extra",
                            iconPath = "icons/lucide/copy-text.svg",
                            tooltip = localizer.getString("main_window_editor_context_menu.copy"),
                            isEnabled = hasExtraText && !mainState.isLoading,
                            isVisible = true,
                            onClick = { mainState.extraOutputText.copyToClipboard() }
                        ),
                        Action(
                            id = "listen_extra",
                            iconPath = "icons/lucide/volume.svg",
                            tooltip = localizer.getString("main_window_editor_context_menu.listen"),
                            isEnabled = hasExtraText && !mainState.isLoading,
                            isVisible = true,
                            onClick = { dispatch(MainIntent.ListenToText(textSource = TextSource.ExtraOutput)) }
                        )
                    )
                )
            )
        )
    }

    fun requestFocusOnInput() {
        inputTextPanel.requestFocusInWindow()
    }

    fun setDictionarySearchWord(word: String) {
        dictionaryPanel.setSearchWord(word)
    }

    private fun showDictionaryWithWord(word: String, language: LanguageCode = currentLookupLanguage) {
        dictionaryPanel.setSearchWord(word)
        if (!dictionaryPanel.isVisible) {
            dispatch(MainIntent.ToggleDictionaryPanel)
        }
        dispatch(MainIntent.LookupWord(word, language))
    }
}