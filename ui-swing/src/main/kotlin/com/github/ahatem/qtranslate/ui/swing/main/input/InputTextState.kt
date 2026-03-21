package com.github.ahatem.qtranslate.ui.swing.main.input

import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.main.widgets.TextActionsState

data class InputTextState(
    val text: String,
    val corrections: List<Correction>,
    val fontConfig: FontConfig,
    val fallbackFontConfig: FontConfig,
    val isEditable: Boolean,
    val isLoading: Boolean,
    val actionsState: TextActionsState
) : UiState