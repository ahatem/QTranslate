package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.arch.UiState

data class SettingsState(
    val isSaving: Boolean = false,
    val configuration: Configuration = Configuration.DEFAULT
) : UiState