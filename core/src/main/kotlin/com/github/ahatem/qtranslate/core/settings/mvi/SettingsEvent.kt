package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.shared.arch.UiEvent

/**
 * One-shot events emitted by [SettingsStore] to be consumed exactly once by the UI.
 *
 * Unlike [SettingsState] (which is persistent and always reflects current truth),
 * events are fire-and-forget — the UI reacts to them and they are not replayed
 * to new collectors.
 */
sealed interface SettingsEvent : UiEvent {

    /**
     * Instructs the UI to close the settings dialog.
     * Emitted after [SettingsIntent.CancelChanges].
     */
    data object CloseSettingsDialog : SettingsEvent

    /**
     * Instructs the UI to display a transient message to the user.
     *
     * @property message The text to display.
     * @property type The severity level, which determines the visual style
     *   (e.g. green for [NotificationType.SUCCESS], red for [NotificationType.ERROR]).
     */
    data class ShowMessage(
        val message: String,
        val type: NotificationType
    ) : SettingsEvent
}