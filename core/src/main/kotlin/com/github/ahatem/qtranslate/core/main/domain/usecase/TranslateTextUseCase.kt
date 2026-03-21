package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
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
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Translates the current input text and updates the [MainState] with the result.
 *
 * ### Job ownership
 * This use case holds a [translationJob] reference to cancel in-flight translations
 * when a new one is requested. **This use case must be a singleton within the
 * [com.github.ahatem.qtranslate.core.main.mvi.MainStore]** — if it is ever recreated,
 * the previous [translationJob] reference is lost and cannot be cancelled, potentially
 * leaving a stale coroutine running that writes to the old state.
 *
 * If the architecture ever requires recreating use cases, move [translationJob] onto
 * the [MainStore] and pass it in as a parameter.
 */
class TranslateTextUseCase(
    private val scope: CoroutineScope,
    private val settingsState: StateFlow<Configuration>,
    private val activeServiceManager: ActiveServiceManager,
    private val historyRepository: HistoryRepository,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("TranslateTextUseCase")
    private var translationJob: Job? = null

    suspend operator fun invoke(
        getState: () -> MainState,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (message: String, type: NotificationType, isTemporary: Boolean) -> Unit,
        textOverride: String? = null
    ) {
        translationJob?.cancel(CancellationException("New translation requested"))

        val textToTranslate = textOverride ?: getState().inputText
        if (textToTranslate.isBlank()) {
            logger.debug("Translation skipped: input text is blank")
            return
        }

        val translator = activeServiceManager.getActiveService<Translator>(ServiceType.TRANSLATOR)
        if (translator == null) {
            logger.warn("No translator service available")
            onStatusUpdate("No translator service is active.", NotificationType.ERROR, true)
            return
        }

        logger.info("Starting translation with '${translator.name}'")

        translationJob = scope.launch {
            try {
                onStatusUpdate("Translating...", NotificationType.INFO, false)
                updateState { copy(isLoading = true, translatedText = "", extraOutputText = "") }

                val currentState = getState()
                val request = TranslationRequest(
                    text           = textToTranslate,
                    sourceLanguage = currentState.sourceLanguage,
                    targetLanguage = currentState.targetLanguage
                )

                logger.debug(
                    "Translation request: ${request.sourceLanguage} → ${request.targetLanguage}, " +
                            "length=${textToTranslate.length}"
                )

                val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
                    translator.translate(request)
                }

                if (result == null) {
                    logger.error("Translation timed out after ${AppConstants.TRANSLATION_TIMEOUT_MS}ms")
                    updateState { copy(isLoading = false) }
                    onStatusUpdate("Translation timed out. Please try again.", NotificationType.ERROR, true)
                    return@launch
                }

                result.fold(
                    success = { response ->
                        logger.info("Translation successful: '${response.translatedText.take(50)}...'")
                        onStatusUpdate("Translation complete.", NotificationType.SUCCESS, true)

                        val detectedLanguage = response.detectedLanguage
                            .takeIf { currentState.sourceLanguage == LanguageCode.AUTO }

                        val (newHistory, newHistoryIndex) = buildHistory(
                            currentState, textToTranslate, response.translatedText, translator.id
                        )

                        val extraOutput = handleExtraOutput(
                            targetText          = response.translatedText,
                            sourceForBackward   = detectedLanguage ?: currentState.sourceLanguage,
                            targetForBackward   = currentState.targetLanguage,
                            translator          = translator,
                            onStatusUpdate      = onStatusUpdate
                        )

                        updateState {
                            copy(
                                isLoading              = false,
                                translatedText         = response.translatedText,
                                detectedSourceLanguage = detectedLanguage,
                                history                = newHistory,
                                historyIndex           = newHistoryIndex,
                                extraOutputText        = extraOutput
                            )
                        }

                        if (settingsState.value.isHistoryEnabled) {
                            historyRepository.saveHistory(newHistory)
                        }
                    },
                    failure = { error ->
                        logger.error("Translation failed: ${error.message}", error.cause)
                        updateState { copy(isLoading = false) }
                        onStatusUpdate("Translation failed: ${error.message}", NotificationType.ERROR, true)
                    }
                )

            } catch (e: CancellationException) {
                logger.debug("Translation cancelled")
                throw e
            } catch (e: Exception) {
                logger.error("Unexpected error during translation", e)
                updateState { copy(isLoading = false) }
                onStatusUpdate("Unexpected error: ${e.message ?: "Unknown error"}", NotificationType.ERROR, true)
            }
        }

        translationJob?.join()
    }

    // -------------------------------------------------------------------------
    // History
    // -------------------------------------------------------------------------

    private fun buildHistory(
        currentState: MainState,
        inputText: String,
        translatedText: String,
        translatorId: String
    ): Pair<List<HistorySnapshot>, Int> {
        if (!settingsState.value.isHistoryEnabled) {
            return currentState.history to currentState.historyIndex
        }

        val snapshot = HistorySnapshot(
            inputText      = inputText,
            translatedText = translatedText,
            sourceLanguage = currentState.sourceLanguage.tag,
            targetLanguage = currentState.targetLanguage.tag,
            translatorId   = translatorId
        )

        // Truncate any "future" entries that were undone before this new translation,
        // then append the new snapshot and cap at the configured maximum.
        val past    = currentState.history.take(currentState.historyIndex)
        val updated = (past + snapshot).takeLast(AppConstants.MAX_HISTORY_ENTRIES)

        logger.debug("History: ${updated.size}/${AppConstants.MAX_HISTORY_ENTRIES} entries")
        return updated to updated.size
    }

    // -------------------------------------------------------------------------
    // Extra output
    // -------------------------------------------------------------------------

    private suspend fun handleExtraOutput(
        targetText: String,
        sourceForBackward: LanguageCode,
        targetForBackward: LanguageCode,
        translator: Translator,
        onStatusUpdate: suspend (message: String, type: NotificationType, isTemporary: Boolean) -> Unit
    ): String = when (settingsState.value.extraOutputType) {
        ExtraOutputType.BackwardTranslate -> performBackwardTranslation(
            targetText        = targetText,
            targetLanguage    = sourceForBackward,
            sourceLanguage    = targetForBackward,
            translator        = translator,
            onStatusUpdate    = onStatusUpdate
        )
        ExtraOutputType.Summarize -> {
            logger.warn("Summarize feature not yet implemented")
            onStatusUpdate("Summary feature is not yet implemented.", NotificationType.WARNING, true)
            ""
        }
        ExtraOutputType.Rewrite -> {
            logger.warn("Rewrite feature not yet implemented")
            onStatusUpdate("Rewrite feature is not yet implemented.", NotificationType.WARNING, true)
            ""
        }
        ExtraOutputType.None -> ""
    }

    private suspend fun performBackwardTranslation(
        targetText: String,
        targetLanguage: LanguageCode,
        sourceLanguage: LanguageCode,
        translator: Translator,
        onStatusUpdate: suspend (message: String, type: NotificationType, isTemporary: Boolean) -> Unit
    ): String {
        if (targetLanguage == LanguageCode.AUTO) {
            logger.warn("Cannot perform backward translation — target language is AUTO")
            return "Cannot translate back to Auto-Detect."
        }

        onStatusUpdate("Performing backward translation...", NotificationType.INFO, false)

        val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            translator.translate(TranslationRequest(targetText, sourceLanguage, targetLanguage))
        }

        return if (result == null) {
            logger.error("Backward translation timed out")
            "Backward translation timed out."
        } else {
            result.fold(
                success = { response ->
                    logger.debug("Backward translation successful")
                    response.translatedText
                },
                failure = { error ->
                    logger.error("Backward translation failed: ${error.message}", error.cause)
                    "Backward translation failed: ${error.message}"
                }
            )
        }
    }
}