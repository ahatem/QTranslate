package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.core.history.HistorySnapshot
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.shared.arch.UiIntent
import java.io.File
import com.github.ahatem.qtranslate.core.document.PdfTranslationMode

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

    /**
     * User cancelled an in-flight translation.
     * Clears [MainState.isLoading] and shows a brief status bar message.
     * No-op if no translation is running.
     */
    data object CancelTranslation : MainIntent

    /**
     * Text was copied to the clipboard and the user should be told.
     *
     * The copy itself happens in the UI, which owns the clipboard; this only reports it so
     * the confirmation travels through the same status-bar path as every other message.
     */
    data object NotifyTextCopied : MainIntent

    /**
     * User stopped TTS playback that is currently in progress.
     * No-op if nothing is playing.
     */
    data object StopTTS : MainIntent

    /** User requested OCR on an image followed by translation of the detected text. */
    data class OcrAndTranslateImage(val image: ImageData) : MainIntent

    /**
     * User requested OCR on an image to extract text and copy it to the clipboard —
     * without triggering a translation. Emits [MainEvent.CopyToClipboard] on success.
     */
    data class OcrAndCopyText(val image: ImageData) : MainIntent

    /**
     * User requested text-to-speech for a specific text panel.
     * @property textSource Which panel to read aloud.
     * @property text Optional text override. If `null`, uses the text from [textSource].
     * @property language Optional language override. If `null`, it is derived from [textSource].
     *   Set by callers whose text belongs to neither panel, such as a dictionary headword.
     */
    data class ListenToText(
        val textSource: TextSource,
        val text: String? = null,
        val language: LanguageCode? = null
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

    /** Translate a supported document using the active translator and language pair. */
    data class TranslateDocument(
        val inputFile: File,
        val outputFile: File,
        val pdfMode: PdfTranslationMode = PdfTranslationMode.LAYOUT_AWARE
    ) : MainIntent

    /** Cancel the active document translation and remove its partial output. */
    data object CancelDocumentTranslation : MainIntent

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

    // ---- Dictionary ----

    /**
     * User requested a dictionary lookup for [word].
     * @property language The language of [word]. Defaults to English.
     */
    data class LookupWord(
        val word: String,
        val language: LanguageCode = LanguageCode("en")
    ) : MainIntent

    /** User toggled the inline dictionary panel open or closed. */
    data object ToggleDictionaryPanel : MainIntent

    /**
     * User triggered the floating dictionary popup (e.g. via global hotkey or auto-lookup).
     * @property selectedText The text that was selected / resolved for lookup.
     * @property language     The language to look the word up in. Defaults to English.
     */
    data class ShowQuickDictionary(
        val selectedText: String,
        val language: LanguageCode = LanguageCode("en")
    ) : MainIntent

    /** User dismissed the floating dictionary popup. */
    data object HideQuickDictionary : MainIntent

    /** User toggled the pin state of the floating dictionary popup. */
    data object ToggleQuickDictionaryPin : MainIntent

    // ---- Image search ----

    /**
     * User asked to see images for [term].
     *
     * @property language The language [term] is in. A term often resolves better in the language
     *   it was written in — species names and medical Latin especially.
     */
    data class SearchImages(
        val term: String,
        val language: LanguageCode = LanguageCode("en")
    ) : MainIntent

    /**
     * User triggered the floating image popup, from the context menu or the global hotkey.
     *
     * @property selectedText The text selected when the popup was asked for.
     * @property language     The language to search in.
     */
    data class ShowImageSearch(
        val selectedText: String,
        val language: LanguageCode = LanguageCode("en")
    ) : MainIntent

    /** User dismissed the floating image popup. */
    data object HideImageSearch : MainIntent

    /** User toggled the pin state of the floating image popup. */
    data object ToggleImageSearchPin : MainIntent

    /**
     * A translation finished and its result is a single word, so a short definition belongs
     * beneath it. A blank [word] clears whatever is showing.
     */
    data class UpdateInlineDefinition(
        val word: String,
        val language: LanguageCode = LanguageCode("en"),
        /**
         * The other word worth defining, tried when [word] yields nothing.
         *
         * A translation has two sides and dictionaries are lopsided: the common ones cover English
         * thoroughly and most other languages barely at all. Defining only the translated word
         * means a reader translating into Arabic, or Hindi, or Vietnamese, sees no definition ever
         * — the lookup is simply asking a dictionary for a language it does not hold.
         */
        val alternateWord: String = "",
        val alternateLanguage: LanguageCode = LanguageCode("en")
    ) : MainIntent
}
