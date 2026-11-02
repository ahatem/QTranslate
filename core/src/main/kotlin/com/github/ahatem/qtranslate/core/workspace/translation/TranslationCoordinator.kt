package com.github.ahatem.qtranslate.core.workspace.translation

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputRequest
import com.github.ahatem.qtranslate.core.shared.StatusCode
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the translation workspace's workflow: which request runs, what its completion means for
 * this origin, and when the secondary panel needs a real translation as a fallback.
 *
 * [TranslateTextUseCase] owns the request lifetime and publication validity; this coordinator
 * owns the policy around it that used to live in the store. It never touches Swing, and the
 * store keeps rendering state and dispatching intents.
 */
internal class TranslationCoordinator(
    private val useCase: TranslateTextUseCase,
    private val settingsState: StateFlow<Configuration>,
    private val getState: () -> MainState,
    private val updateState: (MainState.() -> MainState) -> Unit,
    private val onStatusUpdate: suspend (StatusCode, NotificationType, Boolean) -> Unit,
    private val emitCopy: suspend (String, Long) -> Unit,
) {
    /**
     * Runs one translation and emits a copy event when the origin's auto-copy policy covers it.
     * The completion is returned unchanged so callers can prove a result is theirs.
     */
    suspend fun translate(
        origin: TranslationOrigin,
        textOverride: String? = null,
        extraOutputRequest: ExtraOutputRequest? = null,
        comparisonPolicy: ComparisonPolicy =
            ComparisonPolicy.DISABLED,
        autoCopy: Boolean = true,
        onRequestClaimed: ((Long) -> Unit)? = null,
        start: TranslationStart? = null,
    ): TranslationCompletion? {
        val completion = useCase(
            getState = getState,
            updateState = updateState,
            onStatusUpdate = onStatusUpdate,
            textOverride = textOverride,
            extraOutputRequest = extraOutputRequest,
            comparisonPolicy = comparisonPolicy,
            onRequestClaimed = onRequestClaimed,
            start = start,
        )

        val textToCopy = if (!autoCopy) null else AutoCopyGuard.textToCopy(
            setting = settingsState.value.autoCopyTranslation,
            origin = origin,
            completion = completion,
            translationStillCurrent = completion?.let { useCase.isCurrent(it.requestId) } == true
        )
        // Carry identity into the queue. Checking here cannot authorize a later clipboard write:
        // the delivery envelope revalidates on the effect dispatcher immediately before acceptance.
        if (textToCopy != null && completion != null) {
            emitCopy(textToCopy, completion.requestId)
        }
        return completion
    }

    /**
     * Recomputes the extra panel alone, falling back to a full translation when there is no
     * translation yet to derive one from.
     */
    suspend fun refreshExtraOutput(extraOutputRequest: ExtraOutputRequest?) {
        val refreshed = useCase.refreshExtraOutput(
            extraOutputRequest = extraOutputRequest,
            getState = getState,
            updateState = updateState,
            onStatusUpdate = onStatusUpdate
        )
        if (!refreshed) {
            translate(
                origin = TranslationOrigin.INTERNAL,
                extraOutputRequest = extraOutputRequest,
                comparisonPolicy = ComparisonPolicy.DISABLED
            )
        }
    }

    /** Cancels the primary and secondary requests and normalizes the flags they owned. */
    fun cancel() {
        useCase.cancel()
        updateState {
            copy(
                isExtraOutputLoading = false,
                extraOutputFailed = false,
                extraOutputFailure = null
            )
        }
    }

    /** Retires comparison work; the store clears its rows in the same step. */
    fun invalidateComparisons() = useCase.invalidateComparisons()

    /** Retires secondary work when the primary result it derives from is replaced. */
    fun cancelExtraOutput() = useCase.cancelExtraOutput()

    fun isCurrent(requestId: Long): Boolean = useCase.isCurrent(requestId)

    internal fun reserveStart(): TranslationStart = useCase.reserveStart()

    internal fun <T> withCurrentRequest(requestId: Long, publish: () -> T): T? =
        useCase.withCurrentRequest(requestId, publish)

    /** Identity of the current translation request, used by origin owners that must fence it. */
    internal fun currentRequestId(): Long = useCase.currentRequestId()

    /**
     * Cancels [requestId] only while it still owns the translation. Returns its cancellation identity, or null
     * when a newer translation has already replaced it.
     */
    internal fun cancelIfCurrent(requestId: Long): Long? {
        val cancellation = useCase.cancelIfCurrent(requestId) ?: return null
        useCase.withCurrentRequest(cancellation) {
            updateState {
                copy(isExtraOutputLoading = false, extraOutputFailed = false, extraOutputFailure = null)
            }
        }
        return cancellation
    }
}
