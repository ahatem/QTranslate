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
import com.github.ahatem.qtranslate.ui.swing.main.statusbar.StatusBar
import com.github.ahatem.qtranslate.ui.swing.main.widgets.Action
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.copyToClipboard
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledEditorFallbackFont
import com.github.ahatem.qtranslate.ui.swing.shared.util.scaledEditorFont
import java.awt.BorderLayout
import javax.swing.JPanel

class MainContentView(
    private val iconManager: IconManager,
    private val localizer: LocalizationManager,
    private val dispatch: (MainIntent) -> Unit,
    private val dispatchSettings: (SettingsIntent) -> Unit,
    private val onOpenSnippingTool: () -> Unit,
) : JPanel(BorderLayout()) {

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
        }
    )

    private val outputTextPanel = OutputTextPanel(
        iconManager = iconManager,
        onListen = { text -> dispatch(MainIntent.ListenToText(TextSource.Output, text)) },
        onTranslateRequest = { text ->
            dispatch(MainIntent.UpdateInputText(text))
            dispatch(MainIntent.Translate(text))
        },
    )

    private val extraOutputPanel = ExtraOutputPanel(
        iconManager = iconManager,
        onListen = { text -> dispatch(MainIntent.ListenToText(TextSource.ExtraOutput, text)) },
        onTranslateRequest = { text ->
            dispatch(MainIntent.UpdateInputText(text))
            dispatch(MainIntent.Translate(text))
        },
    )

    val statusBar: StatusBar = StatusBar(
        iconManager = iconManager,
        onNotificationsClicked = { TODO() },
    )

    private val layoutManager = LayoutManager(
        ComponentRegistry(
            historyBar = translationHistoryBar,
            translatorSelector = translatorSelector,
            languageBar = languageSelectionBar,
            inputPanel = inputTextPanel,
            outputPanel = outputTextPanel,
            extraOutputPanel = extraOutputPanel,
            statusBar = statusBar
        ), this
    )

    private var lastState: Pair<MainState, SettingsState>? = null

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

        renderComponents(mainState, config)
        lastState = mainState to settingsState
    }

    private fun renderComponents(mainState: MainState, config: Configuration) {
        val allLanguages = mainState.availableLanguages

        val activePreset = config.getActivePreset()
        val selectedTranslatorId = activePreset?.selectedServices?.get(ServiceType.TRANSLATOR)
        val selectedTranslator = mainState.availableServices.find { it.id == selectedTranslatorId }

        val statusText = localizer.getString(
            "main_window.status_format",
            selectedTranslator?.name ?: localizer.getString("main_window.no_translator"),
            mainState.sourceLanguage.getDisplayName(),
            mainState.targetLanguage.getDisplayName()
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
                text               = mainState.extraOutputText,
                isVisible          = config.extraOutputType != ExtraOutputType.None,
                isLoading          = mainState.isLoading,
                fontConfig         = config.scaledEditorFont,
                fallbackFontConfig = config.scaledEditorFallbackFont,
                activeType         = config.extraOutputType,
                summaryLength      = config.summaryLength,
                rewriteStyle       = config.rewriteStyle,

                labelBackward = localizer.getString("extra_output.label_backward"),
                labelSummary  = localizer.getString("extra_output.label_summary"),
                labelRewrite  = localizer.getString("extra_output.label_rewrite"),

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
                    dispatchSettings(SettingsIntent.UpdateDraft(
                        config.copy(extraOutputType = type)
                    ))
                    dispatch(MainIntent.Translate())
                },
                onSummaryLengthChanged = { length ->
                    dispatchSettings(SettingsIntent.UpdateDraft(
                        config.copy(summaryLength = length)
                    ))
                    dispatch(MainIntent.Translate())
                },
                onRewriteStyleChanged = { style ->
                    dispatchSettings(SettingsIntent.UpdateDraft(
                        config.copy(rewriteStyle = style)
                    ))
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
}