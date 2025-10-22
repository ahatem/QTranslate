package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.shared.arch.UiEvent

/**
 * Events emitted by SettingsStore to the UI layer.
 *
 * Events represent one-time occurrences that the UI should react to,
 * such as showing messages or closing dialogs.
 */
sealed interface SettingsEvent : UiEvent {

    /**
     * Request to close the settings dialog.
     * Typically emitted after successful save or cancel.
     */
    data object CloseSettingsDialog : SettingsEvent

    /**
     * Request to show a message to the user.
     *
     * @property message The message text to display
     * @property type The notification type (SUCCESS, ERROR, WARNING, INFO)
     */
    data class ShowMessage(
        val message: String,
        val type: NotificationType
    ) : SettingsEvent
}