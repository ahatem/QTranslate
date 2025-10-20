package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.shared.arch.UiEvent

sealed interface SettingsEvent : UiEvent {
    data object CloseSettingsDialog : SettingsEvent
    data class ShowMessage(val message: String, val type: NotificationType) : SettingsEvent
}