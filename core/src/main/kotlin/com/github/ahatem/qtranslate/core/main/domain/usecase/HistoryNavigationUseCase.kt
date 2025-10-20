package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType

class HistoryNavigationUseCase {

    fun undo(currentState: MainState): MainState? {
        if (!currentState.canUndo) return null
        return restoreStateFromHistory(currentState, currentState.historyIndex - 1)
    }

    fun redo(currentState: MainState): MainState? {
        if (!currentState.canRedo) return null
        return restoreStateFromHistory(currentState, currentState.historyIndex + 1)
    }

    private fun restoreStateFromHistory(currentState: MainState, newIndex: Int): MainState? {
        if (newIndex !in currentState.history.indices) return null

        val historyEntry = currentState.history[newIndex]
        return currentState.copy(
            inputText = historyEntry.inputText,
            translatedText = historyEntry.translatedText,
            sourceLanguage = LanguageCode(historyEntry.sourceLanguage),
            targetLanguage = LanguageCode(historyEntry.targetLanguage),
            selectedServices = mapOf(ServiceType.TRANSLATOR to historyEntry.translatorId),
            historyIndex = newIndex
        )
    }
}
