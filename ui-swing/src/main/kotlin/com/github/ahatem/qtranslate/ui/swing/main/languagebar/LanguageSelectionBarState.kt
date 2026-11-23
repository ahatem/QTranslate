package com.github.ahatem.qtranslate.ui.swing.main.languagebar

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.LanguageComboBox

data class LanguageSelectionBarState(
    val isLoading: Boolean,
    val canClear: Boolean,
    val canSwap: Boolean,
    /**
     * Whether the Translate action can do anything. False only when the input is
     * blank — a guaranteed no-op the core use case returns from immediately —
     * so the button is not actionable in that case. While loading the button
     * morphs into Cancel and stays actionable regardless of this flag.
     */
    val canTranslate: Boolean = true,
    val allSourceLanguages: List<LanguageCode>,
    val allTargetLanguages: List<LanguageCode>,
    val selectedSourceLanguage: LanguageCode,
    val detectedSourceLanguage: LanguageCode? = null,
    val selectedTargetLanguage: LanguageCode,
    val strings: LanguageSelectionBarStrings
) : UiState {
    /**
     * Whether the Translate/Cancel control can be pressed: always while loading (it is Cancel, and
     * a running translation must stay stoppable), otherwise only when there is input.
     */
    val isTranslateActionable: Boolean get() = isLoading || canTranslate

    /** Whether the language controls accept a change; none do while a translation runs. */
    val areLanguagesEditable: Boolean get() = !isLoading

    /** Whether the swap control can be pressed. */
    val isSwapActionable: Boolean get() = !isLoading && canSwap

    /** Whether the clear control can be pressed: there is input, and no translation is running. */
    val isClearActionable: Boolean get() = !isLoading && canClear
}

data class LanguageSelectionBarStrings(
    val translateButtonText: String,
    val cancelButtonText: String,
    val clearTooltip: String,
    val swapTooltip: String
)

/**
 * Fills a source combo box from [state]. The one place that says the source list carries the
 * detected language and the target list does not.
 */
internal fun LanguageComboBox.renderSource(state: LanguageSelectionBarState) = render(
    availableLanguages = state.allSourceLanguages,
    selectedLanguage = state.selectedSourceLanguage,
    autoDetectedLanguage = state.detectedSourceLanguage,
    isEnabled = state.areLanguagesEditable
)

/** Fills a target combo box from [state]; see [renderSource]. */
internal fun LanguageComboBox.renderTarget(state: LanguageSelectionBarState) = render(
    availableLanguages = state.allTargetLanguages,
    selectedLanguage = state.selectedTargetLanguage,
    autoDetectedLanguage = null,
    isEnabled = state.areLanguagesEditable
)
