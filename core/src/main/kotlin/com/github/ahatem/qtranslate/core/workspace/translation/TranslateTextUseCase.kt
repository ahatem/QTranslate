package com.github.ahatem.qtranslate.core.workspace.translation

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
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputRequest
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import com.github.ahatem.qtranslate.core.shared.util.shortSummary

enum class ComparisonPolicy {
    ENABLED,
    DISABLED
}

data class TranslationCompletion(
    val requestId: Long,
    val translatedText: String
)

/**
 * Translates the current input text and updates the [MainState] with the result.
 *
 * ### Job ownership
 * [TranslationRequestOwner] keeps request identity, comparison generation, and the current
 * job in one ownership transition. A caller that claims request A can never cancel or replace
 * request B's job: its install is refused once B owns the identity, and the job it superseded
 * is detached and cancelled by the claim itself. **This use case must be a singleton within
 * the [com.github.ahatem.qtranslate.core.main.mvi.MainStore]**, because the owner is the only
 * reference to the live job.
 *
 * ### Comparison lifetime
 * Comparison work belongs to one translation generation. A failed or timed-out primary
 * keeps the comparison rows already produced and lets in-flight comparisons publish, since
 * those results remain useful (#367). Only cancellation, a newer translation, or an
 * explicit comparison invalidation discards them.
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
    loggerFactory: LoggerFactory,
    private val parallelComparisonUseCase: ParallelComparisonUseCase? = null
) {
    private val logger: Logger = loggerFactory.getLogger("TranslateTextUseCase")
    private val requestOwner = TranslationRequestOwner()
    private val extraOutputWork = ExtraOutputWorkOwner()

    fun cancel() {
        val canceled = requestOwner.cancel()
        // Outside the ownership lock: comparison ownership is a different subsystem.
        parallelComparisonUseCase?.invalidate(canceled.generation)
        canceled.previousJob?.cancel(CancellationException("Input cleared"))
        retireExtraOutput("Input cleared")
    }

    /** Invalidates comparison work without changing existing primary request id semantics. */
    fun invalidateComparisons() {
        val generation = requestOwner.invalidateGeneration()
        // Outside the ownership lock: comparison ownership is a different subsystem.
        parallelComparisonUseCase?.invalidate(generation)
    }

    /** True only while [requestId] still owns the current translation result. */
    fun isCurrent(requestId: Long): Boolean = requestOwner.isCurrent(requestId)

    /** Accepts a short queued-effect decision under the same lock as request supersession. */
    internal fun <T> withCurrentRequest(requestId: Long, accept: () -> T): T? =
        requestOwner.withCurrentRequest(requestId, accept)

    /** Pure admission only: no cancellation, callbacks, provider work or coroutine starts. */
    internal fun reserveStart(): TranslationStart {
        val secondary = extraOutputWork.claim()
        return TranslationStart(requestOwner.claim(secondary.generation), secondary)
    }

    /** Identity of the current translation request, used by origin owners that must fence it. */
    internal fun currentRequestId(): Long = requestOwner.currentRequestId()

    /**
     * Cancels only the translation identified by [requestId]. Returns a cancellation identity, or null when
     * a newer translation already owns the result, so OCR cannot cancel unrelated work.
     */
    internal fun cancelIfCurrent(requestId: Long): Long? {
        val canceled = requestOwner.cancelIfCurrent(requestId) ?: return null
        // Detach the exact secondary generation before invoking cancellation handlers. If a
        // manual request already took it, leave that request's operation untouched.
        val secondary = extraOutputWork.claimIfCurrent(canceled.secondaryGeneration)
        parallelComparisonUseCase?.invalidate(canceled.generation)
        canceled.previousJob?.cancel(CancellationException("Superseded by a newer OCR workflow"))
        secondary?.previousJob?.cancel(CancellationException("Superseded by a newer OCR workflow"))
        return canceled.requestId
    }

    /**
     * Gates publication on still owning the request. Only the in-memory state write happens
     * under the lock — never a call into another subsystem — so translation and comparison
     * ownership can never be acquired in opposite orders.
     */
    private fun ownedUpdate(
        requestId: Long,
        updateState: (MainState.() -> MainState) -> Unit
    ): (MainState.() -> MainState) -> Unit = { transform ->
        requestOwner.withCurrentRequest(requestId) { updateState(transform) }
    }

    suspend operator fun invoke(
        getState: () -> MainState,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
        textOverride: String? = null,
        extraOutputRequest: ExtraOutputRequest? = null,
        comparisonPolicy: ComparisonPolicy = ComparisonPolicy.ENABLED,
        onRequestClaimed: ((Long) -> Unit)? = null,
        start: TranslationStart? = null,
    ): TranslationCompletion? {
        val inputState = getState()
        val configuration = settingsState.value
        val capturedExtraOutput = extraOutputRequest ?: ExtraOutputRequest.from(configuration)
        val admitted = start ?: reserveStart()
        admitted.cancelPrevious() // Detached jobs only, always outside the OCR/translation locks.
        val extraClaim = admitted.secondary
        val claim = admitted.primary
        if (!requestOwner.isCurrent(claim.requestId)) return null
        val requestId = claim.requestId
        onRequestClaimed?.invoke(requestId)
        val translationGeneration = claim.generation
        // Outside the ownership lock: comparison ownership is a different subsystem.
        parallelComparisonUseCase?.begin(translationGeneration)
        val publish = ownedUpdate(requestId, updateState)
        publish { copy(comparisonResults = emptyList()) }
        val configuredComparisonIds = configuration.getActivePreset()
            ?.comparisonTranslatorIds.orEmpty()

        val textToTranslate = textOverride ?: inputState.inputText
        if (textToTranslate.isBlank()) {
            logger.debug("Translation skipped: input text is blank")
            return null
        }

        // Resolved with its id: history records which service produced each entry, and services
        // no longer carry their own — the host composes it and keys the registry by it.
        val active = activeServiceManager.getActive<Translator>(ServiceRole.TRANSLATOR)
        if (active == null) {
            logger.warn("No translator service available")
            // The job below normally clears translatedText on start; without a
            // translator there is no job, so the stale result of a previous
            // request would otherwise remain publishable as this request's.
            publish { copy(isLoading = false, translatedText = "", translationFailed = true) }
            onStatusUpdate(StatusCode.NoTranslatorActive, NotificationType.ERROR, true)
            return null
        }
        val translator = active.service
        val translatorId = active.id

        logger.info("Starting translation with '${translator.name}'")

        var completion: TranslationCompletion? = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                onStatusUpdate(StatusCode.Translating, NotificationType.INFO, false)
                // Primary startup fields belong to this request. The panel reset belongs to the
                // secondary token claimed above: a standalone refresh that took the panel while
                // this translation was suspended keeps its state.
                publish {
                    copy(
                        isLoading              = true,
                        translatedText         = "",
                        translationFailed      = false
                    )
                }
                extraOutputWork.withCurrent(extraClaim.generation) {
                    updateState {
                        copy(
                            extraOutputText      = "",
                            extraOutputFailed    = false,
                            extraOutputFailure   = null,
                            isExtraOutputLoading = false
                        )
                    }
                }

                val currentState = inputState
                val rules        = configuration.translationRules
                val isAutoDetect = currentState.sourceLanguage == LanguageCode.AUTO

                // When source is explicitly set, we already know the language — apply
                // the rule immediately without needing a first-pass translation.
                val preResolvedTarget = if (!isAutoDetect) {
                    val detectedOrSelected = currentState.detectedSourceLanguage
                        ?: currentState.sourceLanguage
                    resolveTargetFromRules(detectedOrSelected, rules)
                } else null

                val initialTarget = preResolvedTarget ?: currentState.targetLanguage

                if (comparisonPolicy == ComparisonPolicy.ENABLED &&
                    (!isAutoDetect || rules.isEmpty())
                ) {
                    startComparisons(
                        text = textToTranslate,
                        sourceLanguage = currentState.sourceLanguage,
                        targetLanguage = initialTarget,
                        primaryTranslatorId = translatorId,
                        configuredTranslatorIds = configuredComparisonIds,
                        generation = translationGeneration,
                        updateState = publish
                    )
                }

                // Update UI immediately if rule changed the target before translating
                if (preResolvedTarget != null && preResolvedTarget != currentState.targetLanguage) {
                    publish { copy(targetLanguage = preResolvedTarget) }
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
                    // #367: a failed primary keeps this generation's comparison rows and lets
                    // in-flight comparisons finish; only cancellation or a newer generation
                    // discards them (see the CancellationException path and invoke()).
                    publishPrimaryFailure(extraClaim.generation, publish, updateState)
                    onStatusUpdate(StatusCode.TranslationTimeout, NotificationType.ERROR, true)
                    return@launch
                }

                result.fold(
                    success = { response ->
                        logger.info(
                            "Translation successful: translator='${translator.name}', " +
                                    "chars=${response.translatedText.length}"
                        )

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
                                val retranslated = handleReTranslation(
                                    textToTranslate  = textToTranslate,
                                    sourceLanguage   = currentState.sourceLanguage,
                                    ruleTarget       = ruleTarget,
                                    detectedLanguage = detectedLanguage,
                                    currentState     = currentState,
                                    translator       = translator,
                                    translatorId     = translatorId,
                                    updateState      = publish,
                                    panelUpdateState = updateState,
                                    onStatusUpdate   = onStatusUpdate,
                                    extraOutputRequest = capturedExtraOutput,
                                    comparisonPolicy = comparisonPolicy,
                                    translationGeneration = translationGeneration,
                                    configuredComparisonIds = configuredComparisonIds,
                                    inputText = textToTranslate,
                                    historyEnabled = configuration.isHistoryEnabled,
                                    canPersist = { isCurrent(requestId) },
                                    extraOutputToken = extraClaim.generation,
                                )
                                if (retranslated != null) {
                                    completion = TranslationCompletion(requestId, retranslated)
                                }
                                return@launch
                            }
                        }

                        // ---- Normal path — no re-translation needed ----
                        if (comparisonPolicy == ComparisonPolicy.ENABLED &&
                            isAutoDetect && rules.isNotEmpty()
                        ) {
                            startComparisons(
                                text = textToTranslate,
                                sourceLanguage = currentState.sourceLanguage,
                                targetLanguage = initialTarget,
                                primaryTranslatorId = translatorId,
                                configuredTranslatorIds = configuredComparisonIds,
                                generation = translationGeneration,
                                updateState = publish
                            )
                        }
                        onStatusUpdate(StatusCode.TranslationComplete, NotificationType.SUCCESS, true)

                        val (newHistory, newHistoryIndex) = buildHistory(
                            currentState.copy(targetLanguage = initialTarget), textToTranslate, response.translatedText, translatorId,
                            detectedSourceLanguage = detectedLanguage?.tag,
                            historyEnabled = configuration.isHistoryEnabled
                        )

                        // Publish the primary translation before requesting extra output —
                        // the two are independent and the user should not wait for a second
                        // round trip to read the first result.
                        publishPrimaryThenExtraOutput(
                            translatedText    = response.translatedText,
                            detectedLanguage  = detectedLanguage,
                            history           = newHistory,
                            historyIndex      = newHistoryIndex,
                            sourceForBackward = detectedLanguage ?: currentState.sourceLanguage,
                            targetForBackward = initialTarget,
                            translator        = translator,
                            // Primary publication is identity-gated like every other write:
                            // a superseded request cannot paint over the newer result. Panel
                            // writes use the raw update and are gated by the extra-output token.
                            updateState       = publish,
                            panelUpdateState  = updateState,
                            onStatusUpdate    = onStatusUpdate,
                            extraOutputRequest = capturedExtraOutput,
                            inputText = textToTranslate,
                            historyEnabled = configuration.isHistoryEnabled,
                            canPersist = { isCurrent(requestId) },
                            extraOutputToken = extraClaim.generation,
                        )
                        completion = TranslationCompletion(requestId, response.translatedText)
                    },
                    failure = { error ->
                        logger.error("Translation failed: ${error::class.simpleName}")
                        publishPrimaryFailure(extraClaim.generation, publish, updateState)
                        val summary = error.shortSummary()
                        onStatusUpdate(StatusCode.TranslationFailed(summary), NotificationType.ERROR, true)
                    }
                )

            } catch (e: CancellationException) {
                logger.debug("Translation cancelled")
                clearComparisonsIfCurrent(translationGeneration, publish)
                throw e
            } catch (e: Exception) {
                logger.error("Unexpected error during translation: ${e::class.simpleName}")
                publishPrimaryFailure(extraClaim.generation, publish, updateState)
                // An unexpected exception's message is untrusted provider/host text and
                // may carry request URLs (embedded keys) or echoed request content, so
                // the user-visible summary carries metadata only — exactly like the log
                // line above. Typed provider failures keep their message through the
                // Result path; this catch covers contract violations it does not.
                onStatusUpdate(StatusCode.UnexpectedError(e::class.simpleName ?: "Unexpected error"), NotificationType.ERROR, true)
            }
        }
        if (requestOwner.install(claim, job)) {
            job.start()
        } else {
            // Superseded while this request was being constructed: it must never start, so a
            // provider call cannot outlive the request that owns it.
            job.cancel(CancellationException("Translation superseded"))
        }
        job.join()
        return completion?.takeIf { requestOwner.isCurrent(it.requestId) }
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
        translatorId: String,
        updateState: (MainState.() -> MainState) -> Unit,
        panelUpdateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
        extraOutputRequest: ExtraOutputRequest?,
        comparisonPolicy: ComparisonPolicy,
        translationGeneration: Long,
        configuredComparisonIds: List<String>,
        inputText: String,
        historyEnabled: Boolean,
        canPersist: () -> Boolean,
        extraOutputToken: Long,
    ): String? {
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
            publishPrimaryFailure(extraOutputToken, updateState, panelUpdateState)
            onStatusUpdate(StatusCode.TranslationTimeout, NotificationType.ERROR, true)
            return null
        }

        return retryResult.fold(
            success = { retryResponse ->
                logger.info(
                    "Re-translation successful: translator='${translator.name}', " +
                            "chars=${retryResponse.translatedText.length}"
                )
                if (comparisonPolicy == ComparisonPolicy.ENABLED) {
                    startComparisons(
                        text = textToTranslate,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = ruleTarget,
                        primaryTranslatorId = translatorId,
                        configuredTranslatorIds = configuredComparisonIds,
                        generation = translationGeneration,
                        updateState = updateState
                    )
                }
                onStatusUpdate(StatusCode.TranslationComplete, NotificationType.SUCCESS, true)

                val (newHistory, newHistoryIndex) = buildHistory(
                    currentState.copy(targetLanguage = ruleTarget), textToTranslate, retryResponse.translatedText, translatorId,
                    detectedSourceLanguage = detectedLanguage.tag,
                    historyEnabled = historyEnabled
                )

                publishPrimaryThenExtraOutput(
                    translatedText    = retryResponse.translatedText,
                    detectedLanguage  = detectedLanguage,
                    history           = newHistory,
                    historyIndex      = newHistoryIndex,
                    sourceForBackward = detectedLanguage,
                    targetForBackward = ruleTarget,
                    translator        = translator,
                    updateState       = updateState,
                    panelUpdateState  = panelUpdateState,
                    onStatusUpdate    = onStatusUpdate,
                    extraOutputRequest = extraOutputRequest,
                    ruleTarget        = ruleTarget,
                    inputText         = textToTranslate,
                    historyEnabled    = historyEnabled,
                    canPersist        = canPersist,
                    extraOutputToken  = extraOutputToken
                )
                retryResponse.translatedText
            },
            failure = { error ->
                logger.error("Re-translation failed: ${error::class.simpleName}")
                publishPrimaryFailure(extraOutputToken, updateState, panelUpdateState)
                val summary = error.shortSummary()
                onStatusUpdate(StatusCode.TranslationFailed(summary), NotificationType.ERROR, true)
                null
            }
        )
    }

    /**
     * Publishes a primary failure without lowering the spinner of a newer secondary request.
     *
     * The primary failure fields belong to the translation request; [MainState.isExtraOutputLoading]
     * belongs to [extraOutputWork]. A standalone refresh that claimed the panel since this
     * translation started now owns that spinner, so the failure lowers it only while the
     * translation's token is still current. [updateState] is the request-gated primary
     * publication and [panelUpdateState] is the raw panel write; the two updates are sequential,
     * so the request lock is released before the extra-output lock is acquired.
     */
    private fun publishPrimaryFailure(
        extraOutputToken: Long,
        updateState: (MainState.() -> MainState) -> Unit,
        panelUpdateState: (MainState.() -> MainState) -> Unit
    ) {
        updateState { copy(isLoading = false, translationFailed = true) }
        extraOutputWork.withCurrent(extraOutputToken) {
            panelUpdateState { copy(isExtraOutputLoading = false) }
        }
    }

    private suspend fun startComparisons(
        text: String,
        sourceLanguage: LanguageCode,
        targetLanguage: LanguageCode,
        primaryTranslatorId: String,
        configuredTranslatorIds: List<String>,
        generation: Long,
        updateState: (MainState.() -> MainState) -> Unit
    ) {
        parallelComparisonUseCase?.start(
            request = ParallelComparisonRequest(
                text = text,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                primaryTranslatorId = primaryTranslatorId,
                translatorIds = configuredTranslatorIds
            ),
            generation = generation,
            updateState = { results ->
                requestOwner.withCurrentGeneration(generation) {
                    updateState { copy(comparisonResults = results) }
                }
            }
        )
    }

    /**
     * Discards comparison work for [generation]: clears its rows and stops its comparisons.
     *
     * Only cancellation uses this. A failed or timed-out primary keeps the generation's
     * comparison rows and lets in-flight comparisons finish, because their results stay
     * useful to the user (#367). Stale generations are always ignored.
     */
    private fun clearComparisonsIfCurrent(
        generation: Long,
        updateState: (MainState.() -> MainState) -> Unit
    ) {
        if (requestOwner.withCurrentGeneration(generation) { true } != true) return
        parallelComparisonUseCase?.invalidate(generation)
        updateState { copy(comparisonResults = emptyList()) }
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
        translatorId: String,
        detectedSourceLanguage: String? = null,
        historyEnabled: Boolean
    ): Pair<List<HistorySnapshot>, Int> {
        if (!historyEnabled) {
            return currentState.history to currentState.historyIndex
        }

        val snapshot = HistorySnapshot(
            inputText              = inputText,
            translatedText         = translatedText,
            sourceLanguage         = currentState.sourceLanguage.tag,
            targetLanguage         = currentState.targetLanguage.tag,
            translatorId           = translatorId,
            detectedSourceLanguage = detectedSourceLanguage
        )

        // Truncate any "future" entries that were undone before this new translation,
        // then append the new snapshot and cap at the configured maximum.
        val past    = currentState.history.take(currentState.historyIndex)
        val updated = (past + snapshot).takeLast(AppConstants.MAX_HISTORY_ENTRIES)

        logger.debug("History: ${updated.size}/${AppConstants.MAX_HISTORY_ENTRIES} entries")
        return updated to updated.size
    }

    /** Patches the last snapshot in [history] with the resolved extra output. */
    private fun patchExtraOutput(
        history: List<HistorySnapshot>,
        extraOutputText: String,
        extraOutputType: String
    ): List<HistorySnapshot> {
        if (history.isEmpty() || (extraOutputText.isEmpty() && extraOutputType == "None")) return history
        val patched = history.last().copy(
            extraOutputText = extraOutputText,
            extraOutputType = extraOutputType
        )
        return history.dropLast(1) + patched
    }

    // -------------------------------------------------------------------------
    // Extra output
    // -------------------------------------------------------------------------

    /**
     * Claims the secondary panel and detaches the operation it supersedes. The caller cancels
     * the returned previous job outside the ownership lock.
     */
    private fun retireExtraOutput(reason: String): ExtraOutputWorkOwner.Claim {
        val claim = extraOutputWork.claim()
        claim.previousJob?.cancel(CancellationException(reason))
        return claim
    }

    /** Retires in-flight secondary work when the primary result it derives from is replaced. */
    fun cancelExtraOutput() {
        retireExtraOutput("Extra output invalidated")
    }

    /**
     * Recomputes only the extra output, leaving the translation on screen untouched.
     *
     * Switching the extra panel between backward, summary and rewrite used to dispatch a full
     * translation. That wiped the translation the user was reading, showed a spinner over it, and
     * spent a second request on the translator for a result already on screen — on the unofficial
     * endpoints, a rate limit risked for nothing. Changing summary length or rewrite style did the
     * same, which is harder still to justify, since only the extra panel's own parameter moved.
     *
     * Nothing about the extra output needs the translation to be redone: [handleExtraOutput] takes
     * the translated text as a parameter, so it can be fed the text already in state.
     *
     * [extraOutputRequest] may carry settings that have just been committed or selected. This
     * avoids reading previous Extra Output values while the derived settings flow propagates.
     *
     * Returns false when there is nothing to work from, leaving the caller to fall back to a real
     * translation rather than showing an empty panel.
     */
    suspend fun refreshExtraOutput(
        extraOutputRequest: ExtraOutputRequest? = null,
        getState: () -> MainState,
        updateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit
    ): Boolean {
        val state = getState()
        val request = extraOutputRequest ?: ExtraOutputRequest.from(settingsState.value)
        val claim = retireExtraOutput("Extra output type changed")

        // Switching the panel off owns the result immediately. Whatever is in flight is already
        // stale, so neither its answer nor its cleanup may write into the cleared panel.
        if (request.type == ExtraOutputType.None) {
            extraOutputWork.withCurrent(claim.generation) {
                updateState {
                    copy(
                        extraOutputText = "",
                        extraOutputFailed = false,
                        extraOutputFailure = null,
                        isExtraOutputLoading = false
                    )
                }
            }
            return true
        }

        // Nothing translated yet, so there is no result to derive from. Summarising the input
        // would still need the input, and backward translation needs the output either way.
        if (state.translatedText.isBlank()) {
            extraOutputWork.withCurrent(claim.generation) {
                updateState {
                    copy(isExtraOutputLoading = false, extraOutputFailed = false, extraOutputFailure = null)
                }
            }
            return false
        }

        val translator = activeServiceManager.getActive<Translator>(ServiceRole.TRANSLATOR)?.service
            ?: run {
                logger.warn("No translator service available")
                onStatusUpdate(StatusCode.NoTranslatorActive, NotificationType.ERROR, true)
                extraOutputWork.withCurrent(claim.generation) {
                    updateState {
                        copy(
                            extraOutputText = "",
                            extraOutputFailed = true,
                            extraOutputFailure = ExtraOutputFailure.NoTranslator,
                            isExtraOutputLoading = false
                        )
                    }
                }
                return true
            }

        // The slot and its generation move together: a claim superseded while this job is being
        // built can neither install itself over the newer job nor leave a provider request
        // running after its result stopped being publishable. The initial loading state belongs
        // to that same token: an older job that was already running must not reopen a panel a
        // newer request has taken over.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ownsPanel = extraOutputWork.withCurrent(claim.generation) {
                updateState {
                    copy(
                        extraOutputText = "",
                        extraOutputFailed = false,
                        extraOutputFailure = null,
                        isExtraOutputLoading = true
                    )
                }
            } != null
            if (!ownsPanel) return@launch
            val outcome = try {
                handleExtraOutput(
                    request           = request,
                    inputText         = state.inputText,
                    targetText        = state.translatedText,
                    sourceForBackward = state.detectedSourceLanguage ?: state.sourceLanguage,
                    targetForBackward = state.targetLanguage,
                    translator        = translator,
                    onStatusUpdate    = onStatusUpdate
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                ExtraOutputResult.Failure(ExtraOutputFailure.Unexpected)
            }
            publishExtraOutput(outcome, state.history, request, claim.generation, updateState)
        }
        if (!extraOutputWork.register(claim, job)) {
            // A newer request already owns the panel: never start a provider call whose result
            // could only be discarded.
            job.cancel(CancellationException("Extra output superseded"))
            return true
        }
        job.start()
        return true
    }

    /**
     * Writes one secondary outcome, but only while its request still owns the panel.
     *
     * A failed request clears the text and records the message separately: copy, speech and
     * history must not pick up an error line as if it were content.
     */
    private suspend fun publishExtraOutput(
        outcome: ExtraOutputResult,
        history: List<HistorySnapshot>,
        request: ExtraOutputRequest,
        token: Long,
        updateState: (MainState.() -> MainState) -> Unit,
        persistFailureHistory: Boolean = false,
        historyEnabled: Boolean = settingsState.value.isHistoryEnabled
    ): Boolean {
        var patched: List<HistorySnapshot>? = null
        val published = extraOutputWork.withCurrent(token) {
            when (outcome) {
                is ExtraOutputResult.Success -> {
                    val newHistory = if (historyEnabled) {
                        patchExtraOutput(history, outcome.text, request.type.name)
                    } else null
                    patched = newHistory
                    updateState {
                        copy(
                            extraOutputText      = outcome.text,
                            extraOutputFailed    = false,
                            extraOutputFailure   = null,
                            isExtraOutputLoading = false,
                            history              = newHistory ?: history
                        )
                    }
                }
                is ExtraOutputResult.Failure -> {
                    patched = if (persistFailureHistory) history else null
                    updateState {
                        copy(
                            extraOutputText      = "",
                            extraOutputFailed    = true,
                            extraOutputFailure   = outcome.reason,
                            isExtraOutputLoading = false
                        )
                    }
                }
            }
            true
        }
        if (published == true && patched != null && historyEnabled) persistHistory(patched!!)
        return published == true
    }

    /**
     * Publishes the primary translation immediately, then fills in the extra output when it
     * arrives.
     *
     * The two results come from independent requests, so waiting for the second before showing
     * the first made every translation feel as slow as the slowest of the pair. The primary
     * result is written first and [MainState.isExtraOutputLoading] carries the secondary
     * request, letting the extra panel show its own progress while the translation is already
     * readable.
     *
     * Runs inside the caller's translation job, so a new translation cancels an in-flight
     * extra-output request and a stale result can never overwrite a newer translation.
     *
     * The primary snapshot is persisted independently of the secondary outcome: a successful
     * secondary patches it before saving, while a failed or superseded one still saves the
     * primary translation. Primary fields are gated by request identity; panel fields are gated
     * only by the secondary token, and the two writes are never combined into one snapshot.
     *
     * @param ruleTarget Set only on the rule-driven path, where the target language changed
     *   as a result of the detected source language.
     */
    private suspend fun publishPrimaryThenExtraOutput(
        translatedText: String,
        detectedLanguage: LanguageCode?,
        history: List<HistorySnapshot>,
        historyIndex: Int,
        sourceForBackward: LanguageCode,
        targetForBackward: LanguageCode,
        translator: Translator,
        updateState: (MainState.() -> MainState) -> Unit,
        panelUpdateState: (MainState.() -> MainState) -> Unit,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
        extraOutputRequest: ExtraOutputRequest? = null,
        ruleTarget: LanguageCode? = null,
        inputText: String,
        historyEnabled: Boolean,
        canPersist: () -> Boolean = { true },
        extraOutputToken: Long
    ) {
        val request = extraOutputRequest ?: ExtraOutputRequest.from(settingsState.value)
        val expectsExtraOutput = request.type != ExtraOutputType.None
        // The token was claimed when this translation started, so a superseded job cannot
        // retire a newer request's secondary panel. It only publishes when still current.
        val token = extraOutputToken

        // Primary fields are independent of the panel, so publish them without touching the
        // secondary state. `updateState` is the request-gated primary publication.
        updateState {
            copy(
                isLoading              = false,
                translationFailed      = false,
                translatedText         = translatedText,
                detectedSourceLanguage = detectedLanguage,
                targetLanguage         = ruleTarget ?: targetLanguage,
                history                = history,
                historyIndex           = historyIndex
            )
        }

        // The panel reset is a secondary-panel mutation and must hold the same token as its
        // publication. A standalone refresh that claimed the panel while the primary result was
        // being written keeps its state.
        val ownsPanel = extraOutputWork.withCurrent(token) {
            panelUpdateState {
                copy(
                    extraOutputText      = "",
                    extraOutputFailed    = false,
                    extraOutputFailure   = null,
                    isExtraOutputLoading = expectsExtraOutput
                )
            }
        } != null

        if (!expectsExtraOutput || !ownsPanel) {
            if (historyEnabled && canPersist()) persistHistory(history)
            return
        }

        // The secondary request is a real child job registered with the owner, not anonymous
        // work inside the translation job: switching mode or undoing must cancel the provider
        // call itself, not merely suppress its result.
        val outcome = coroutineScope {
            val extraJob = async(start = CoroutineStart.LAZY) {
                try {
                    handleExtraOutput(
                        request           = request,
                        inputText         = inputText,
                        targetText        = translatedText,
                        sourceForBackward = sourceForBackward,
                        targetForBackward = targetForBackward,
                        translator        = translator,
                        onStatusUpdate    = onStatusUpdate
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    // A provider that throws is a failed secondary output, not a failed
                    // primary translation.
                    ExtraOutputResult.Failure(ExtraOutputFailure.Unexpected)
                }
            }
            if (!extraOutputWork.register(token, extraJob)) {
                extraJob.cancel(CancellationException("Extra output superseded"))
                return@coroutineScope null
            }
            extraJob.start()
            try {
                extraJob.await()
            } catch (cancellation: CancellationException) {
                // Only a supersede/cancel of the secondary job is swallowed; the translation
                // job being cancelled must still propagate.
                currentCoroutineContext().ensureActive()
                null
            }
        }

        if (outcome == null) {
            // Superseded while running: lower only our own spinner and keep the primary
            // history, which is independent of the secondary result.
            extraOutputWork.withCurrent(token) {
                panelUpdateState { copy(isExtraOutputLoading = false) }
            }
            if (historyEnabled && canPersist()) persistHistory(history)
            return
        }

        val published = publishExtraOutput(
            outcome = outcome,
            history = history,
            request = request,
            token = token,
            updateState = panelUpdateState,
            persistFailureHistory = true,
            historyEnabled = historyEnabled
        )
        if (!published && settingsState.value.isHistoryEnabled) {
            // A newer claim owns the panel, but the primary snapshot still belongs in history.
            persistHistory(history)
        }
    }

    // History is best effort: its repository records failure, while translation stays successful.
    private suspend fun persistHistory(history: List<com.github.ahatem.qtranslate.core.history.HistorySnapshot>) {
        historyRepository.saveHistory(history).fold(success = { }, failure = { })
    }

    private suspend fun handleExtraOutput(
        request: ExtraOutputRequest,
        inputText: String,
        targetText: String,
        sourceForBackward: LanguageCode,
        targetForBackward: LanguageCode,
        translator: Translator,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
    ): ExtraOutputResult {
        // ExtraOutputSource determines whether we operate on the original input text
        // or on the translated output. Both are captured by the owning request.
        val sourceText = when (request.source) {
            ExtraOutputSource.Output -> targetText
            ExtraOutputSource.Input  -> inputText
        }
        return when (request.type) {
            ExtraOutputType.BackwardTranslate -> performBackwardTranslation(
                targetText     = targetText,
                targetLanguage = sourceForBackward,
                sourceLanguage = targetForBackward,
                translator     = translator,
                onStatusUpdate = onStatusUpdate
            )
            ExtraOutputType.Summarize -> summarizeUseCase(
                text           = sourceText,
                summaryLength  = request.summaryLength,
                onStatusUpdate = onStatusUpdate
            )
            ExtraOutputType.Rewrite -> rewriteUseCase(
                text           = sourceText,
                rewriteStyle   = request.rewriteStyle,
                onStatusUpdate = onStatusUpdate
            )
            ExtraOutputType.None -> ExtraOutputResult.Success("")
        }
    }

    private suspend fun performBackwardTranslation(
        targetText: String,
        targetLanguage: LanguageCode,
        sourceLanguage: LanguageCode,
        translator: Translator,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit,
    ): ExtraOutputResult {
        if (targetLanguage == LanguageCode.AUTO) {
            logger.warn("Cannot perform backward translation — target language is AUTO")
            return ExtraOutputResult.Failure(ExtraOutputFailure.InvalidTarget)
        }

        onStatusUpdate(StatusCode.PerformingBackwardTranslation, NotificationType.INFO, false)

        val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            translator.translate(TranslationRequest(targetText, sourceLanguage, targetLanguage))
        }

        return if (result == null) {
            logger.error("Backward translation timed out")
            // A failure must not be announced as a success just because the primary
            // translation succeeded earlier.
            onStatusUpdate(StatusCode.BackwardTranslationTimeout, NotificationType.ERROR, true)
            ExtraOutputResult.Failure(ExtraOutputFailure.Timeout)
        } else {
            result.fold(
                success = { response ->
                    logger.debug("Backward translation successful")
                    // The primary translation announced its own completion; this one replaces
                    // the temporary "performing" status with a temporary completion of its own.
                    onStatusUpdate(StatusCode.BackwardTranslationComplete, NotificationType.SUCCESS, true)
                    ExtraOutputResult.Success(response.translatedText)
                },
                failure = { error ->
                    logger.error("Backward translation failed: ${error::class.simpleName}")
                    val summary = error.shortSummary()
                    onStatusUpdate(StatusCode.BackwardTranslationFailed(summary), NotificationType.ERROR, true)
                    ExtraOutputResult.Failure(ExtraOutputFailure.Provider(summary))
                }
            )
        }
    }
}
