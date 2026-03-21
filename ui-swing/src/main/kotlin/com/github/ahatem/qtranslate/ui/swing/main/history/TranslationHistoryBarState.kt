package com.github.ahatem.qtranslate.ui.swing.main.history

import com.github.ahatem.qtranslate.core.shared.arch.UiState

data class TranslationHistoryBarState(
    val statusText: String,
    val canGoBackward: Boolean,
    val canGoForward: Boolean,
    val isLoading: Boolean,
    val strings: TranslationHistoryBarStrings,
) : UiState

data class TranslationHistoryBarStrings(
    val backwardTooltip: String,
    val forwardTooltip: String,
    val imageTranslateTooltip: String,
)