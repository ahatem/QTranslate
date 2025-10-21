package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
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
import com.github.michaelbull.result.fold
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

        val textToTranslate = textOverride ?: getState().inputText
        if (textToTranslate.isBlank()) return

        val translator = activeServiceManager.getActiveService<Translator>(ServiceType.TRANSLATOR, getState())
        if (translator == null) {
            onStatusUpdate("No translator service is active.", NotificationType.ERROR)
            return
        }

        translationJob = scope.launch {
            onStatusUpdate("Translating...", NotificationType.INFO)
            updateState { copy(isLoading = true, translatedText = "", extraOutputText = "") }

            val request = TranslationRequest(
                text = textToTranslate,
                sourceLanguage = getState().sourceLanguage,
                targetLanguage = getState().targetLanguage
            )

            translator.translate(request)
                .onSuccess { response ->
                    val currentState = getState()
                    onStatusUpdate("Translation successful!", NotificationType.SUCCESS)

                    // --- START: REFACTORED LOGIC ---

                    // 1. Determine the newly detected language
                    val newDetectedLanguage = if (currentState.sourceLanguage == LanguageCode.AUTO) {
                        response.detectedLanguage
                    } else {
                        null
                    }

                    // 2. Calculate the new history state (but don't apply it yet)
                    val (newHistory, newHistoryIndex) = calculateNewHistory(
                        currentState,
                        textToTranslate,
                        response.translatedText
                    )

                    // 3. Perform backward translation to get the extra output text
                    val newExtraOutput = handleExtraOutput(
                        targetText = response.translatedText,
                        sourceForBackward = newDetectedLanguage ?: currentState.sourceLanguage, // Use detected lang!
                        targetForBackward = currentState.targetLanguage,
                        translator = translator,
                        onStatusUpdate = onStatusUpdate
                    )

                    // 4. Atomically update the state with ALL new information at once
                    updateState {
                        copy(
                            isLoading = false,
                            translatedText = response.translatedText,
                            detectedSourceLanguage = newDetectedLanguage,
                            history = newHistory,
                            historyIndex = newHistoryIndex,
                            extraOutputText = newExtraOutput
                        )
                    }

                    // 5. Save history as a side-effect after the state is updated
                    if (settingsState.value.isHistoryEnabled) {
                        historyRepository.saveHistory(getState().history)
                    }
                    // --- END: REFACTORED LOGIC ---
                }
                .onFailure { error ->
                    updateState { copy(isLoading = false) }
                    onStatusUpdate("Translation failed: ${error.message}", NotificationType.ERROR)
                }
        }
        translationJob?.join()
    }

    private fun calculateNewHistory(
        currentState: MainState,
        inputText: String,
        translatedText: String
    ): Pair<List<HistorySnapshot>, Int> {
        if (!settingsState.value.isHistoryEnabled) {
            return currentState.history to currentState.historyIndex
        }

        val newSnapshot = HistorySnapshot(
            inputText = inputText,
            translatedText = translatedText,
            sourceLanguage = currentState.sourceLanguage.tag,
            targetLanguage = currentState.targetLanguage.tag,
            translatorId = currentState.getSelectedServiceFor(ServiceType.TRANSLATOR)?.id ?: ""
        )

        val pastHistory = currentState.history.take(currentState.historyIndex)
        val updatedHistory = (pastHistory + newSnapshot).takeLast(100) // Your history limit

        val newIndex = updatedHistory.size

        return updatedHistory to newIndex
    }

    private suspend fun handleExtraOutput(
        targetText: String,
        sourceForBackward: LanguageCode,
        targetForBackward: LanguageCode,
        translator: Translator,
        onStatusUpdate: suspend (message: String, type: NotificationType) -> Unit
    ): String {
        return when (settingsState.value.extraOutputType) {
            ExtraOutputType.BackwardTranslate -> performBackwardTranslation(
                targetText = targetText,
                targetLanguage = sourceForBackward,
                sourceLanguage = targetForBackward,
                translator = translator,
                onStatusUpdate = onStatusUpdate
            )
            ExtraOutputType.None -> ""
            else -> {
                onStatusUpdate(
                    "${settingsState.value.extraOutputType} is not yet implemented.",
                    NotificationType.WARNING
                )
                ""
            }
        }
    }

    private suspend fun performBackwardTranslation(
        targetText: String,
        targetLanguage: LanguageCode,
        sourceLanguage: LanguageCode,
        translator: Translator,
        onStatusUpdate: suspend (message: String, type: NotificationType) -> Unit
    ): String {
        if (targetLanguage == LanguageCode.AUTO) return "Cannot translate back to Auto-Detect."

        onStatusUpdate("Performing backward translation...", NotificationType.INFO)
        val request = TranslationRequest(targetText, sourceLanguage, targetLanguage)
        return translator.translate(request)
            .fold(
                success = { it.translatedText },
                failure = { "Backward translation failed." }
            )
    }
}