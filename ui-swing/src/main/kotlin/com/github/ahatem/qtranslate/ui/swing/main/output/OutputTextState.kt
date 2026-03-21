package com.github.ahatem.qtranslate.ui.swing.main.output

import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsState

data class OutputTextState(
    val text: String,
    val fontConfig: FontConfig,
    val fallbackFontConfig: FontConfig,
    val isLoading: Boolean,
    val actionsState: TextActionsState
) : UiState

data class ExtraOutputState(
    val text: String,
    val fontConfig: FontConfig,
    val fallbackFontConfig: FontConfig,
    val isLoading: Boolean,
    val isVisible: Boolean,
    val actionsState: TextActionsState
) : UiState