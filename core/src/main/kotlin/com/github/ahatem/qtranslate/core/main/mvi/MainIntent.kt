package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.core.history.HistorySnapshot
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.shared.arch.UiIntent

/**
 * All user actions that can be dispatched to [MainStore].
 */
sealed interface MainIntent : UiIntent {

    // ---- Text input & languages ----

    /** User typed or pasted text into the input field. */
    data class UpdateInputText(val text: String) : MainIntent

    /** User selected a source language from the dropdown. */
    data class SelectSourceLanguage(val language: LanguageCode) : MainIntent

    /** User selected a target language from the dropdown. */
    data class SelectTargetLanguage(val language: LanguageCode) : MainIntent

    /** User clicked the swap button to exchange source and target languages. */
    data object SwapLanguages : MainIntent

    // ---- Translation & processing ----

    /**
     * User requested a translation.
     * @property text Optional text override. If `null`, uses [MainState.inputText].
     */
    data class Translate(val text: String? = null) : MainIntent

    /** User requested OCR on an image followed by translation of the detected text. */
    data class OcrAndTranslateImage(val image: ImageData) : MainIntent

    /**
     * User requested text-to-speech for a specific text panel.
     * @property textSource Which panel to read aloud.
     * @property text Optional text override. If `null`, uses the text from [textSource].
     */
    data class ListenToText(
        val textSource: TextSource,
        val text: String? = null
    ) : MainIntent

    /** User manually triggered a spell check (or it was triggered automatically). */
    data object PerformSpellCheck : MainIntent

    /**
     * User clicked a spell-check suggestion to apply it.
     * @property original   The misspelled word as it appears in [MainState.inputText].
     * @property suggestion The correction to substitute in.
     */
    data class ApplyCorrection(
        val original: String,
        val suggestion: String
    ) : MainIntent

    // ---- History navigation ----

    /** User clicked the back button to undo the last translation. */
    data object UndoTranslation : MainIntent

    /**
     * User clicked the forward button to redo a translation.
     * Redoing past the last history entry clears the input — see [MainStore.handleRedo].
     */
    data object RedoTranslation : MainIntent

    /**
     * User selected a specific entry in the History dialog to restore.
     * Restores [snapshot] into the editor and moves [MainState.historyIndex]
     * to the matching position so undo/redo continues to work correctly.
     */
    data class RestoreHistoryEntry(val snapshot: HistorySnapshot) : MainIntent

    /** User clicked "Clear All" in the History dialog. */
    data object ClearHistory : MainIntent

    // ---- Application actions ----

    /** User requested a check for application updates. */
    data object CheckForUpdates : MainIntent

    // ---- Quick translate popup ----

    /**
     * User triggered quick translate (e.g. via global hotkey with text selected).
     * @property selectedText The text that was selected at the time of the hotkey press.
     */
    data class ShowQuickTranslate(val selectedText: String) : MainIntent

    /** User dismissed the quick translate popup. */
    data object HideQuickTranslate : MainIntent

    /** User triggered inline translation — selected text will be replaced with its translation. */
    data class ReplaceWithTranslation(val selectedText: String) : MainIntent

    /** User cycled to the next available target language. */
    data object CycleTargetLanguage : MainIntent

    /** User toggled the pin state of the quick translate popup. */
    data object ToggleQuickTranslateDialogPin : MainIntent
}