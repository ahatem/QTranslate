package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationCompletion

/**
 * Keeps automatic speech tied to the selection and translation request that asked for it.
 *
 * The guard is deliberately pure so cancellation, supersession, and blank results can be
 * covered without involving an audio device.
 */
internal object SelectionTranslationReadGuard {
    fun shouldReadSource(
        readRequested: Boolean,
        selectionGeneration: Long,
        currentSelectionGeneration: Long,
        selectedText: String
    ): Boolean =
        readRequested &&
            selectionGeneration == currentSelectionGeneration &&
            selectedText.isNotBlank()

    fun shouldRead(
        readRequested: Boolean,
        selectionGeneration: Long,
        currentSelectionGeneration: Long,
        completion: TranslationCompletion?,
        translationStillCurrent: Boolean
    ): Boolean =
        readRequested &&
            selectionGeneration == currentSelectionGeneration &&
            completion != null &&
            translationStillCurrent &&
            completion.translatedText.isNotBlank()
}
