package com.github.ahatem.qtranslate.ui.swing.main.statusbar

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.shared.arch.UiState

data class StatusBarState(
    val message: String,
    val type: NotificationType,
    val isLoading: Boolean = false,
    val notificationTooltip: String,
    val isNotificationButtonEnabled: Boolean
) : UiState

