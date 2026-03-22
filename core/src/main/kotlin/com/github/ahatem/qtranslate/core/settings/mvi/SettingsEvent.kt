package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.arch.UiEvent

/**
 * One-shot events emitted by [SettingsStore] to be consumed exactly once by the UI.
 */
sealed interface SettingsEvent : UiEvent {

    /**
     * Emitted after [SettingsIntent.CancelChanges].
     *
     * Carries the [originalConfiguration] so the dialog can revert any side
     * effects that were applied for live preview (e.g. language change).
     * The dialog should close after handling this event.
     */
    data class ChangesReverted(
        val originalConfiguration: Configuration
    ) : SettingsEvent

    /**
     * Instructs the UI to display a transient message to the user.
     */
    data class ShowMessage(
        val message: String,
        val type: NotificationType
    ) : SettingsEvent
}