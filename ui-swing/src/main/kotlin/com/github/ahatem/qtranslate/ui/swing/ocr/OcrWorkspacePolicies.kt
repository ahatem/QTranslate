package com.github.ahatem.qtranslate.ui.swing.ocr

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceError
import com.github.ahatem.qtranslate.core.ocr.workspace.OcrWorkspaceState
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * Presentation decisions of the OCR Workspace, kept free of Swing components so they can be
 * tested without building the dialog.
 */

internal enum class OcrStatusTone { MUTED, BUSY, ERROR }

/** The fix the status line offers next to its message, if any. */
internal enum class OcrStatusAction { NONE, OPEN_SETTINGS }

/** One status line: a localization key, an optional language argument and how to present it. */
internal data class OcrStatus(
    val key: String,
    val language: LanguageCode? = null,
    val tone: OcrStatusTone = OcrStatusTone.MUTED,
    val action: OcrStatusAction = OcrStatusAction.NONE,
)

/** The localization key that explains [error] in the workspace. */
internal fun ocrErrorKey(error: OcrWorkspaceError): String = when (error) {
    OcrWorkspaceError.NoImage -> "ocr_workspace.no_image"
    OcrWorkspaceError.NoProvider -> "ocr_workspace.no_provider"
    OcrWorkspaceError.NoText -> "ocr_workspace.no_text_found"
    OcrWorkspaceError.Timeout -> "status_bar.ocr_timeout"
    OcrWorkspaceError.ImageOpenFailed -> "ocr_workspace.image_open_failed"
    OcrWorkspaceError.ClipboardEmpty -> "ocr_workspace.clipboard_empty"
    is OcrWorkspaceError.Failed -> "ocr_workspace.recognition_failed"
}

/** What the status line says for [state], most urgent first. */
internal fun ocrStatus(state: OcrWorkspaceState): OcrStatus {
    val error = state.error
    return when {
        state.isLoadingImage -> OcrStatus("ocr_workspace.loading_image", tone = OcrStatusTone.BUSY)
        state.isRecognizing -> OcrStatus("ocr_workspace.recognizing", tone = OcrStatusTone.BUSY)
        error != null -> OcrStatus(
            key = ocrErrorKey(error),
            tone = OcrStatusTone.ERROR,
            action = if (error == OcrWorkspaceError.NoProvider) OcrStatusAction.OPEN_SETTINGS else OcrStatusAction.NONE,
        )
        // Say it before the first image arrives, not after the user has captured one.
        state.needsProvider -> OcrStatus("ocr_workspace.no_provider", action = OcrStatusAction.OPEN_SETTINGS)
        state.isEdited && state.hasText -> OcrStatus("ocr_workspace.edited")
        state.hasText -> state.recognizedLanguage
            ?.takeIf { it != LanguageCode.AUTO }
            ?.let { OcrStatus("ocr_workspace.recognized_language", language = it) }
            ?: OcrStatus("ocr_workspace.recognized")
        !state.hasImage -> OcrStatus("ocr_workspace.ready")
        else -> OcrStatus("ocr_workspace.no_text")
    }
}

/** What Escape does, most specific first: it stops work before it closes the window. */
internal enum class OcrEscapeAction { CANCEL_RECOGNITION, CANCEL_TRANSLATION, CLOSE }

internal fun resolveOcrEscape(isRecognizing: Boolean, isTranslatingHere: Boolean): OcrEscapeAction = when {
    isRecognizing -> OcrEscapeAction.CANCEL_RECOGNITION
    isTranslatingHere -> OcrEscapeAction.CANCEL_TRANSLATION
    else -> OcrEscapeAction.CLOSE
}

/** Window-wide workspace chords that compete with text editing. */
internal enum class OcrShortcut { RECOGNIZE, OPEN_IMAGE, SAVE_IMAGE, PASTE_IMAGE }

/**
 * Whether [shortcut] may fire while focus is in a text editor.
 *
 * Opening or saving only shows a file chooser, so nothing changes until the user confirms and
 * the chord stays usable while typing. A re-run replaces the text, so it must never discard
 * edits the user typed. Ctrl+V belongs to the editor; its image fallback is [resolveOcrPaste].
 */
internal fun ocrShortcutAllowed(shortcut: OcrShortcut, editingText: Boolean, textEdited: Boolean): Boolean =
    when (shortcut) {
        OcrShortcut.OPEN_IMAGE, OcrShortcut.SAVE_IMAGE -> true
        OcrShortcut.RECOGNIZE -> !editingText || !textEdited
        OcrShortcut.PASTE_IMAGE -> !editingText
    }

internal enum class OcrPasteTarget { TEXT, IMAGE }

/**
 * Where Ctrl+V in the recognized-text editor goes. Text always pastes as text; a clipboard that
 * holds only an image (or image file) becomes the workspace's new image instead of doing nothing.
 */
internal fun resolveOcrPaste(clipboardHasText: Boolean, clipboardHasImage: Boolean): OcrPasteTarget =
    if (!clipboardHasText && clipboardHasImage) OcrPasteTarget.IMAGE else OcrPasteTarget.TEXT

/** "Recognize (Ctrl+R)": a tooltip that teaches the chord bound to the same action. */
internal fun withShortcut(text: String, stroke: KeyStroke): String {
    val modifiers = KeyEvent.getModifiersExText(stroke.modifiers)
    val key = KeyEvent.getKeyText(stroke.keyCode)
    val chord = if (modifiers.isBlank()) key else "$modifiers+$key"
    return "$text ($chord)"
}
