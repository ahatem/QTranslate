package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.history.HistorySnapshot
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TranslateTextUseCase(
    private val scope: CoroutineScope,
    private val settingsState: StateFlow<Configuration>,
    private val activeServiceManager: ActiveServiceManager,
    private val historyRepository: HistoryRepository
) {
    private var translationJob: Job? = null

    suspend operator fun invoke(
        getState: () -> MainState,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (message: String, type: NotificationType) -> Unit,
        textOverride: String? = null
    ) {
        translationJob?.cancel(CancellationException("New translation requested"))

        val currentState = getState()
        val textToTranslate = textOverride ?: currentState.inputText

        if (textToTranslate.isBlank()) {
            return
        }

        val translator = activeServiceManager.getActiveService<Translator>(ServiceType.TRANSLATOR, currentState)
        if (translator == null) {
            onStatusUpdate("No translator service is active.", NotificationType.ERROR)
            return
        }

        translationJob = scope.launch {
            onStatusUpdate("Translating...", NotificationType.INFO)
            updateState { copy(isLoading = true, translatedText = "", extraOutputText = "") }
//            delay(5000) // TODO: For testing purposes, remove this delay

            val request = TranslationRequest(textToTranslate, currentState.sourceLanguage, currentState.targetLanguage)
            translator.translate(request)
                .onSuccess { response ->
                    updateState {
                        copy(
                            isLoading = false,
                            translatedText = response.translatedText,
                            sourceLanguage = response.detectedLanguage ?: sourceLanguage
                        )
                    }
                    onStatusUpdate("Translation successful!", NotificationType.SUCCESS)

                    addSnapshotToHistory(textToTranslate, response.translatedText, getState, updateState)
                    handleExtraOutput(response.translatedText, getState, updateState, onStatusUpdate)
                }
                .onFailure { error ->
                    updateState { copy(isLoading = false) }
                    onStatusUpdate("Translation failed: ${error.message}", NotificationType.ERROR)
                }
        }

        translationJob?.join()
    }

    private suspend fun addSnapshotToHistory(
        inputText: String,
        translatedText: String,
        getState: () -> MainState,
        updateState: (MainState.() -> MainState) -> Unit
    ) {
        if (!settingsState.value.isHistoryEnabled) return

        val currentState = getState()
        val newSnapshot = HistorySnapshot(
            inputText = inputText,
            translatedText = translatedText,
            sourceLanguage = currentState.sourceLanguage.tag,
            targetLanguage = currentState.targetLanguage.tag,
            translatorId = currentState.getSelectedServiceFor(ServiceType.TRANSLATOR)?.id ?: ""
        )

        val updatedHistory = (currentState.history.take(currentState.historyIndex + 1) + newSnapshot).takeLast(100)
        updateState {
            copy(history = updatedHistory, historyIndex = updatedHistory.lastIndex)
        }

        historyRepository.saveHistory(getState().history)
    }

    private suspend fun handleExtraOutput(
        targetText: String,
        getState: () -> MainState,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (message: String, type: NotificationType) -> Unit
    ) {
        when (settingsState.value.extraOutputType) {
            ExtraOutputType.BackwardTranslate -> performBackwardTranslation(
                targetText,
                getState,
                updateState,
                onStatusUpdate
            )

            ExtraOutputType.Summarize, ExtraOutputType.Rewrite -> {
                onStatusUpdate(
                    "${settingsState.value.extraOutputType} is not yet implemented.",
                    NotificationType.WARNING
                )
            }

            ExtraOutputType.None -> Unit
        }
    }

    private suspend fun performBackwardTranslation(
        targetText: String,
        getState: () -> MainState,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (message: String, type: NotificationType) -> Unit
    ) {
        onStatusUpdate("Performing backward translation...", NotificationType.INFO)
        val currentState = getState()
        val translator =
            activeServiceManager.getActiveService<Translator>(ServiceType.TRANSLATOR, currentState) ?: return

        val request = TranslationRequest(targetText, currentState.targetLanguage, currentState.sourceLanguage)
        translator.translate(request)
            .onSuccess { backResponse ->
                updateState { copy(extraOutputText = backResponse.translatedText) }
            }
            .onFailure {
                updateState { copy(extraOutputText = "Backward translation failed.") }
            }
    }
}