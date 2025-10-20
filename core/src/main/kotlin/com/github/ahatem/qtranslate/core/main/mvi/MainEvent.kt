package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.shared.arch.UiEvent

sealed interface MainEvent : UiEvent {
    data class UpdateStatusBar(val text: String, val type: NotificationType = NotificationType.INFO) : MainEvent
}