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
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
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
 *
 * ### Translation Rules
 * When translation rules are configured, the use case resolves the correct target
 * language before translating. If the source is Auto Detect, the detected language
 * from the first translation response is used to check for a matching rule — if one
 * is found, a second translation is performed with the correct target automatically.
 * This second call only happens when:
 * 1. Source is set to Auto Detect
 * 2. A rule matches the detected language
 * 3. The rule's target differs from the current target
 */
class TranslateTextUseCase(
    private val scope: CoroutineScope,
    private val settingsState: StateFlow<Configuration>,
    private val activeServiceManager: ActiveServiceManager,
    private val historyRepository: HistoryRepository,
    private val summarizeUseCase: SummarizeUseCase,
    private val rewriteUseCase: RewriteUseCase,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("TranslateTextUseCase")
    private var translationJob: Job? = null

    fun cancel() {
        translationJob?.cancel(CancellationException("Input cleared"))
        translationJob = null
    }

    // Stored so handleExtraOutput can access current input text for ExtraOutputSource.Input
    private var currentGetState: (() -> MainState)? = null

    suspend operator fun invoke(
        getState: () -> MainState,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
        textOverride: String? = null
    ) {
        currentGetState = getState
        translationJob?.cancel(CancellationException("New translation requested"))

        val textToTranslate = textOverride ?: getState().inputText
        if (textToTranslate.isBlank()) {
            logger.debug("Translation skipped: input text is blank")
            return
        }

        val translator = activeServiceManager.getActiveService<Translator>(ServiceType.TRANSLATOR)
        if (translator == null) {
            logger.warn("No translator service available")
            onStatusUpdate(StatusCode.NoTranslatorActive, NotificationType.ERROR, true)
            return
        }

        logger.info("Starting translation with '${translator.name}'")

        translationJob = scope.launch {
            try {
                onStatusUpdate(StatusCode.Translating, NotificationType.INFO, false)
                updateState { copy(isLoading = true, translatedText = "", extraOutputText = "") }

                val currentState = getState()
                val rules        = settingsState.value.translationRules
                val isAutoDetect = currentState.sourceLanguage == LanguageCode.AUTO

                // When source is explicitly set, we already know the language — apply
                // the rule immediately without needing a first-pass translation.
                val preResolvedTarget = if (!isAutoDetect) {
                    val detectedOrSelected = currentState.detectedSourceLanguage
                        ?: currentState.sourceLanguage
                    resolveTargetFromRules(detectedOrSelected, rules)
                } else null

                val initialTarget = preResolvedTarget ?: currentState.targetLanguage

                // Update UI immediately if rule changed the target before translating
                if (preResolvedTarget != null && preResolvedTarget != currentState.targetLanguage) {
                    updateState { copy(targetLanguage = preResolvedTarget) }
                }

                val request = TranslationRequest(
                    text           = textToTranslate,
                    sourceLanguage = currentState.sourceLanguage,
                    targetLanguage = initialTarget
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
                    onStatusUpdate(StatusCode.TranslationTimeout, NotificationType.ERROR, true)
                    return@launch
                }

                result.fold(
                    success = { response ->
                        logger.info("Translation successful: '${response.translatedText.take(50)}...'")

                        val detectedLanguage = response.detectedLanguage
                            .takeIf { isAutoDetect }

                        // Re-translate if Auto Detect revealed a rule match
                        // Only triggers when:
                        // 1. Source was Auto Detect
                        // 2. A language was detected from the response
                        // 3. A rule matches the detected language
                        // 4. The rule target differs from what we just translated to
                        if (isAutoDetect && detectedLanguage != null) {
                            val ruleTarget = resolveTargetFromRules(detectedLanguage, rules)
                            if (ruleTarget != null && ruleTarget != initialTarget) {
                                logger.debug(
                                    "Rule matched detected '${detectedLanguage.tag}' → " +
                                            "re-translating to '${ruleTarget.tag}'"
                                )
                                handleReTranslation(
                                    textToTranslate  = textToTranslate,
                                    sourceLanguage   = currentState.sourceLanguage,
                                    ruleTarget       = ruleTarget,
                                    detectedLanguage = detectedLanguage,
                                    currentState     = currentState,
                                    translator       = translator,
                                    updateState      = updateState,
                                    onStatusUpdate   = onStatusUpdate
                                )
                                return@launch
                            }
                        }

                        // ---- Normal path — no re-translation needed ----
                        onStatusUpdate(StatusCode.TranslationComplete, NotificationType.SUCCESS, true)

                        val (newHistory, newHistoryIndex) = buildHistory(
                            currentState, textToTranslate, response.translatedText, translator.id
                        )

                        val extraOutput = handleExtraOutput(
                            targetText        = response.translatedText,
                            sourceForBackward = detectedLanguage ?: currentState.sourceLanguage,
                            targetForBackward = initialTarget,
                            translator        = translator,
                            onStatusUpdate    = onStatusUpdate
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
                        val summary = error.message?.lines()?.firstOrNull()?.take(120) ?: "Unknown error"
                        onStatusUpdate(StatusCode.TranslationFailed(summary), NotificationType.ERROR, true)
                    }
                )

            } catch (e: CancellationException) {
                logger.debug("Translation cancelled")
                throw e
            } catch (e: Exception) {
                logger.error("Unexpected error during translation", e)
                updateState { copy(isLoading = false) }
                val summary = e.message?.lines()?.firstOrNull()?.take(120) ?: "Unknown error"
                onStatusUpdate(StatusCode.UnexpectedError(summary), NotificationType.ERROR, true)
            }
        }

        translationJob?.join()
    }

    /**
     * Performs a second translation when Auto Detect revealed a rule match.
     * Updates state with the final result and correct target language.
     */
    private suspend fun handleReTranslation(
        textToTranslate: String,
        sourceLanguage: LanguageCode,
        ruleTarget: LanguageCode,
        detectedLanguage: LanguageCode,
        currentState: MainState,
        translator: Translator,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
    ) {
        val retryRequest = TranslationRequest(
            text           = textToTranslate,
            sourceLanguage = sourceLanguage,
            targetLanguage = ruleTarget
        )

        val retryResult = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            translator.translate(retryRequest)
        }

        if (retryResult == null) {
            logger.error("Re-translation timed out")
            updateState { copy(isLoading = false) }
            onStatusUpdate(StatusCode.TranslationTimeout, NotificationType.ERROR, true)
            return
        }

        retryResult.fold(
            success = { retryResponse ->
                logger.info("Re-translation successful: '${retryResponse.translatedText.take(50)}...'")
                onStatusUpdate(StatusCode.TranslationComplete, NotificationType.SUCCESS, true)

                val (newHistory, newHistoryIndex) = buildHistory(
                    currentState, textToTranslate, retryResponse.translatedText, translator.id
                )

                val extraOutput = handleExtraOutput(
                    targetText        = retryResponse.translatedText,
                    sourceForBackward = detectedLanguage,
                    targetForBackward = ruleTarget,
                    translator        = translator,
                    onStatusUpdate    = onStatusUpdate
                )

                updateState {
                    copy(
                        isLoading              = false,
                        translatedText         = retryResponse.translatedText,
                        detectedSourceLanguage = detectedLanguage,
                        targetLanguage         = ruleTarget,
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
                logger.error("Re-translation failed: ${error.message}", error.cause)
                updateState { copy(isLoading = false) }
                val summary = error.message?.lines()?.firstOrNull()?.take(120) ?: "Unknown error"
                onStatusUpdate(StatusCode.TranslationFailed(summary), NotificationType.ERROR, true)
            }
        )
    }

    // -------------------------------------------------------------------------
    // Rules
    // -------------------------------------------------------------------------

    private fun resolveTargetFromRules(
        detectedSource: LanguageCode,
        rules: List<TranslationRule>
    ): LanguageCode? =
        rules.firstOrNull { it.sourceLanguage == detectedSource.tag }
            ?.let { LanguageCode(it.targetLanguage) }

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
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
    ): String {
        val config = settingsState.value
        // ExtraOutputSource determines whether we operate on the original input text
        // or on the translated output. Resolved by the caller before this is called.
        val sourceText = when (config.extraOutputSource) {
            ExtraOutputSource.Output -> targetText
            ExtraOutputSource.Input  -> currentGetState?.invoke()?.inputText ?: ""
        }
        return when (config.extraOutputType) {
            ExtraOutputType.BackwardTranslate -> performBackwardTranslation(
                targetText     = targetText,
                targetLanguage = sourceForBackward,
                sourceLanguage = targetForBackward,
                translator     = translator,
                onStatusUpdate = onStatusUpdate
            )
            ExtraOutputType.Summarize -> summarizeUseCase(
                text           = sourceText,
                config         = config,
                onStatusUpdate = onStatusUpdate
            )
            ExtraOutputType.Rewrite -> rewriteUseCase(
                text           = sourceText,
                config         = config,
                onStatusUpdate = onStatusUpdate
            )
            ExtraOutputType.None -> ""
        }
    }

    private suspend fun performBackwardTranslation(
        targetText: String,
        targetLanguage: LanguageCode,
        sourceLanguage: LanguageCode,
        translator: Translator,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
    ): String {
        if (targetLanguage == LanguageCode.AUTO) {
            logger.warn("Cannot perform backward translation — target language is AUTO")
            return "Cannot translate back to Auto-Detect."
        }

        onStatusUpdate(StatusCode.PerformingBackwardTranslation, NotificationType.INFO, false)

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