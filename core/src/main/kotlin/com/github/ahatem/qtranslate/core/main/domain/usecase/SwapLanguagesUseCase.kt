package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.mvi.MainState

/**
 * Swaps source and target languages, moves the current translation back into the
 * input field, and triggers a new translation.
 *
 * This is stateless — no dependencies, pure logic applied to the current [MainState].
 *
 * ### Constraints
 * - Cannot swap if [MainState.sourceLanguage] is [LanguageCode.AUTO] — there is no
 *   specific language to swap to.
 * - Cannot swap if [MainState.translatedText] is blank — there is nothing to put in
 *   the input field.
 *
 * ### State update ordering
 * [onStateUpdate] is called with the new state before [onTranslateNeeded] is invoked.
 * The caller ([MainStore]) must ensure that [onTranslateNeeded] reads the updated state
 * (i.e. [MainState.inputText] = old translated text) rather than the pre-swap state.
 * Since [MainStore] updates [_state] synchronously in [onStateUpdate] and
 * [onTranslateNeeded] reads [_state.value] asynchronously, this ordering is safe as
 * long as both callbacks are invoked on the same thread/dispatcher.
 */
class SwapLanguagesUseCase {

    operator fun invoke(
        currentState: MainState,
        onStateUpdate: (MainState) -> Unit,
        onTranslateNeeded: () -> Unit
    ) {
        if (currentState.sourceLanguage == LanguageCode.AUTO) return
        if (currentState.translatedText.isBlank()) return

        onStateUpdate(
            currentState.copy(
                sourceLanguage         = currentState.targetLanguage,
                targetLanguage         = currentState.sourceLanguage,
                inputText              = currentState.translatedText,
                translatedText         = "",
                extraOutputText        = "",
                detectedSourceLanguage = null,
                spellCheckCorrections  = emptyList()
            )
        )

        onTranslateNeeded()
    }
}