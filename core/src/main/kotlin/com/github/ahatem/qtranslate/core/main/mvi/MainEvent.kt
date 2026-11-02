package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRTextRegion
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.arch.UiEvent
import java.io.File

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
        val downloadUrl: String?,
        val releaseUrl: String? = null
    ) : MainEvent

    data class UpdateStatusBar(
        val code: StatusCode,
        val type: NotificationType = NotificationType.INFO,
        val isTemporary: Boolean = true
    ) : MainEvent

    /**
     * Instructs the UI to copy [text] to the system clipboard. The UI owns the clipboard.
     */
    data class CopyToClipboard(val text: String) : MainEvent

    /** Instructs the UI to put [image] on the clipboard without OCR or translation. */
    data class CopyImageToClipboard(val image: ImageData) : MainEvent

    /**
     * Opens or refreshes the OCR Workspace.
     *
     * @param initialText When non-null, an OCR result is already available and the workspace can
     *   adopt it instead of immediately running recognition again.
     * @param initialLanguage The language that OCR result belongs to, when known.
     * @param autoTranslate Translate the initial text after the workspace adopts it.
     */
    data class OpenOcrWorkspace(
        val image: ImageData,
        val autoTranslate: Boolean,
        val initialText: String? = null,
        val initialRegions: List<OCRTextRegion> = emptyList(),
        val initialLanguage: LanguageCode? = null,
    ) : MainEvent

    /** Presents the main window through the canonical show/focus path. */
    data object ShowMainWindow : MainEvent

    data class DocumentTranslationCompleted(val outputFile: File) : MainEvent

    data class DocumentTranslationFailed(val message: String) : MainEvent
}
