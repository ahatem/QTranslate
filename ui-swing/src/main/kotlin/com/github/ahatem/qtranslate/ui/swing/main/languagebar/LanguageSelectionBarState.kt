package com.github.ahatem.qtranslate.ui.swing.main.languagebar

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.shared.arch.UiState

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
) : UiState

data class LanguageSelectionBarStrings(
    val translateButtonText: String,
    val cancelButtonText: String,
    val clearTooltip: String,
    val swapTooltip: String
)