package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.shared.arch.UiEvent

/**
 * One-shot events emitted by [MainStore] to be consumed exactly once by the UI.
 */
sealed interface MainEvent : UiEvent {

    /**
     * Instructs the UI to display a message in the status bar.
     *
     * @property message The text to display.
     * @property type    Severity level — affects colour and icon in the status bar.
     * @property isTemporary If `true`, the message should auto-clear after
     *   [com.github.ahatem.qtranslate.core.shared.AppConstants.STATUS_MESSAGE_DURATION_MS].
     *   If `false`, it persists until replaced by the next status update.
     */
    /**
     * Instructs the UI to paste [translatedText] back, replacing the previously
     * selected text. Emitted after [MainIntent.ReplaceWithTranslation] completes.
     */
    data class PasteTranslation(val translatedText: String) : MainEvent

    data class UpdateStatusBar(
        val message: String,
        val type: NotificationType = NotificationType.INFO,
        val isTemporary: Boolean = true
    ) : MainEvent
}