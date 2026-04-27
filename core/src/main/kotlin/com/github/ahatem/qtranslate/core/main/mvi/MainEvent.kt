package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.arch.UiEvent

/**
 * One-shot events emitted by [MainStore] to be consumed exactly once by the UI.
 */
sealed interface MainEvent : UiEvent {

    /**
     * Instructs the UI to paste [translatedText] back, replacing the previously
     * selected text. Emitted after [MainIntent.ReplaceWithTranslation] completes.
     */
    data class PasteTranslation(val translatedText: String) : MainEvent

    data class ShowUpdateDialog(
        val newVersion: String,
        val currentVersion: String,
        val releaseNotes: String,
        val downloadUrl: String?
    ) : MainEvent

    data class UpdateStatusBar(
        val code: StatusCode,
        val type: NotificationType = NotificationType.INFO,
        val isTemporary: Boolean = true
    ) : MainEvent
}