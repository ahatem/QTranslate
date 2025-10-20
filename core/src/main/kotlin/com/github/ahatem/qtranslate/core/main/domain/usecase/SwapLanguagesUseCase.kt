package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.core.main.mvi.MainState

class SwapLanguagesUseCase {

    operator fun invoke(
        currentState: MainState,
        onStateUpdate: (MainState) -> Unit,
        onTranslateNeeded: () -> Unit
    ) {
        if (currentState.translatedText.isBlank()) {
            return
        }

        val newState = currentState.copy(
            sourceLanguage = currentState.targetLanguage,
            targetLanguage = currentState.sourceLanguage,
            inputText = currentState.translatedText,
            translatedText = "",
            extraOutputText = ""
        )

        onStateUpdate(newState)
        onTranslateNeeded()
    }
}
