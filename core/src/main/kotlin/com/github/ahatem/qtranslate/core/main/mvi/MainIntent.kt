package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.arch.UiIntent

/** User actions for the main translation screen */
sealed interface MainIntent : UiIntent {

    /** Update the main input text */
    data class UpdateInputText(val text: String) : MainIntent

    /** Change source language */
    data class SelectSourceLanguage(val language: LanguageCode) : MainIntent

    /** Change target language */
    data class SelectTargetLanguage(val language: LanguageCode) : MainIntent

    /** Swap source and target languages */
    object SwapLanguages : MainIntent

    /** Perform OCR and translate the image */
    data class OcrAndTranslateImage(val image: ImageData) : MainIntent

    /** Select active service for a category */
    data class SelectService(val type: ServiceType, val serviceId: String?) : MainIntent

    /** Translate text, optionally overriding input */
    data class Translate(val text: String? = null) : MainIntent

    /** Listen to text from a source, optionally overridden */
    data class ListenToText(val textSource: TextSource, val text: String? = null) : MainIntent

    /** Apply a spell-check correction */
    data class ApplyCorrection(val original: String, val suggestion: String) : MainIntent

    /** Navigate backward in history */
    object UndoTranslation : MainIntent

    /** Navigate forward in history */
    object RedoTranslation : MainIntent

    /** Check for updates */
    object CheckForUpdates : MainIntent

    /** Show Quick Translate dialog with selected text */
    data class ShowQuickTranslate(val selectedText: String) : MainIntent

    /** Hide Quick Translate dialog */
    object HideQuickTranslate : MainIntent

    /** Toggle pin state of Quick Translate dialog */
    object ToggleQuickTranslateDialogPin : MainIntent
}
