package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Captures the selected text by synthesizing Copy, without leaving any internal content on
 * the user's clipboard.
 *
 * Trigger release synchronization lives with the caller (hotkey actions wait for the
 * triggering chord to go physically neutral first), so this class performs no settle delay
 * of its own: snapshot is taken immediately, then Copy, capture, restore, callback.
 *
 * A snapshot that cannot be acquired ([ClipboardSnapshotResult.Failed]) aborts the capture
 * before Copy is synthesized: overwriting clipboard state that cannot be restored is worse
 * than capturing nothing. Likewise an unavailable change monitor fails the capture instead
 * of accepting whatever text happens to be on the clipboard.
 *
 * Restoration is retried a bounded number of times for temporary clipboard-busy failures and
 * runs cancellation-shielded, so cancellation never strands the clipboard in the captured
 * state. The capture itself stays cancellable. Restoration reports success: when the original
 * clipboard cannot be put back, the captured selection is not dispatched, because the
 * restore-before-callback invariant means the action must never observe clipboard state the
 * user did not leave there.
 *
 * Concurrent requests are serialized, so two hotkeys cannot fight over clipboard ownership.
 *
 * **Known limitation: change attribution.** [ClipboardChangeMonitor] proves only that the
 * clipboard's generation advanced after the capture token was taken; it has no way to prove
 * that the synthesized Copy is what advanced it. A clipboard manager, another process, or an
 * unrelated user action landing inside the same short window is read back exactly like our own
 * Copy would be. This is a platform-level gap, not something the current architecture resolves;
 * [CaptureResult] is worded to avoid claiming certainty this class does not have.
 */
class SelectionCapture(
    private val clipboard: SystemClipboard,
    private val changeMonitor: ClipboardChangeMonitor,
    /**
     * Runs one Copy attempt and reports whether it definitely failed. `true` covers genuine
     * success as well as a delivery-uncertain outcome the injector has decided not to retry
     * (see [com.github.ahatem.qtranslate.ui.swing.main.CopyInjector]); only `false` means Copy
     * definitely never went through. Either way the clipboard change observation below remains
     * the actual completion signal — this return value only distinguishes a known-failed
     * attempt from one that might have landed.
     */
    private val simulateCopy: suspend () -> Boolean,
    private val logger: Logger,
    private val mutex: Mutex = Mutex(),
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val captureTimeoutMs: Long = DEFAULT_CAPTURE_TIMEOUT_MS,
    private val restoreMaxAttempts: Int = DEFAULT_RESTORE_MAX_ATTEMPTS,
    private val restoreRetryDelayMs: Long = DEFAULT_RESTORE_RETRY_DELAY_MS,
) {

    /**
     * Runs one capture. The callback receives [CaptureResult.Success] with the text read back
     * after a confirmed clipboard change, [CaptureResult.NoUsableText] when that same confirmed
     * change produced nothing usable, or [CaptureResult.Failed] with a category (never clipboard
     * contents) when the capture could not run, could not complete, or could not be confirmed —
     * an unconfirmed Copy, a known-failed injection, or a clipboard read that itself errored are
     * all reported as [CaptureResult.Failed], never guessed as [CaptureResult.NoUsableText].
     */
    suspend fun capture(callback: (CaptureResult) -> Unit) {
        mutex.withLock {
            val snapshot = readSnapshot()
            if (snapshot is ClipboardSnapshotResult.Failed) {
                logger.warn("Clipboard snapshot failed, skipping selection capture: ${snapshot.cause?.message}")
                currentCoroutineContext().ensureActive()
                callback(CaptureResult.Failed(CaptureFailure.SNAPSHOT_FAILED))
                return
            }

            val token = changeMonitor.mark()
            if (token == null) {
                // No synthetic Copy has happened yet, so there is nothing to restore:
                // abort without touching the clipboard at all.
                logger.warn("Clipboard change monitor unavailable, skipping selection capture")
                currentCoroutineContext().ensureActive()
                callback(CaptureResult.Failed(CaptureFailure.MONITOR_UNAVAILABLE))
                return
            }

            val outcome: CopyOutcome = try {
                copyAndRead(token)
            } catch (cancelled: CancellationException) {
                restoreShielded(snapshot)
                throw cancelled
            } catch (e: Exception) {
                logger.warn("Selection capture failed: ${e.message}")
                restoreShielded(snapshot)
                currentCoroutineContext().ensureActive()
                callback(CaptureResult.Failed(CaptureFailure.CAPTURE_ERROR))
                return
            }

            val restored = restoreShielded(snapshot)
            currentCoroutineContext().ensureActive()
            callback(
                if (!restored) {
                    CaptureResult.Failed(CaptureFailure.RESTORE_FAILED)
                } else {
                    when (outcome) {
                        is CopyOutcome.Captured -> CaptureResult.Success(outcome.text)
                        // The clipboard's generation advanced and what could be read back was
                        // unusable: the one case with an observed change to report on.
                        is CopyOutcome.ConfirmedEmpty -> CaptureResult.NoUsableText
                        is CopyOutcome.InjectionFailed ->
                            CaptureResult.Failed(CaptureFailure.COPY_INJECTION_FAILED)
                        is CopyOutcome.Unconfirmed ->
                            CaptureResult.Failed(CaptureFailure.COPY_UNCONFIRMED)
                        is CopyOutcome.ReadFailed ->
                            CaptureResult.Failed(CaptureFailure.CAPTURE_ERROR)
                    }
                }
            )
        }
    }

    /** What one Copy-and-observe round produced; see [CaptureResult] for how each maps out. */
    private sealed interface CopyOutcome {
        data class Captured(val text: String) : CopyOutcome
        data object ConfirmedEmpty : CopyOutcome
        data object InjectionFailed : CopyOutcome
        data object Unconfirmed : CopyOutcome
        data class ReadFailed(val cause: Throwable) : CopyOutcome
    }

    private suspend fun copyAndRead(token: Long): CopyOutcome {
        if (!simulateCopy()) {
            // The injector knows, definitely, that Copy never went through (no fallback
            // configured, or the fallback itself failed): polling for a change it cannot have
            // caused would only spend the capture window without learning anything.
            logger.warn("Copy injection failed; selection capture inconclusive")
            return CopyOutcome.InjectionFailed
        }

        var waited = 0L
        while (true) {
            // With delayed rendering the sequence may not advance until the clipboard data
            // is actually requested, while the source application waits for that request
            // before rendering. Reading here triggers rendering; the outcome is always
            // discarded: a nudge read failing here is not itself decisive, since nothing has
            // been confirmed to change yet.
            runCatching { clipboard.readText() }
            if (changeMonitor.hasChangedSince(token)) {
                // The generation advanced: something landed. Only now does a read failure mean
                // anything, and what it means is that the capture could not be completed — not
                // that there was nothing to read.
                return when (val read = readConfirmedText()) {
                    is ClipboardReadOutcome.Text -> CopyOutcome.Captured(read.text)
                    is ClipboardReadOutcome.Empty -> CopyOutcome.ConfirmedEmpty
                    is ClipboardReadOutcome.Failed -> {
                        logger.warn(
                            "Clipboard read failed after a confirmed change; " +
                                "selection capture inconclusive: ${read.cause.message}"
                        )
                        CopyOutcome.ReadFailed(read.cause)
                    }
                }
            }
            if (waited >= captureTimeoutMs) {
                // Silence, not evidence: Copy was believed sent but no clipboard generation
                // change was ever observed. This is not proof that nothing was selected.
                logger.debug("No confirmed clipboard change within the capture window")
                return CopyOutcome.Unconfirmed
            }
            delay(pollIntervalMs)
            waited += pollIntervalMs
        }
    }

    private fun readSnapshot(): ClipboardSnapshotResult =
        runCatching { clipboard.snapshot() }
            .getOrElse { ClipboardSnapshotResult.Failed(it) }

    /** A clipboard read taken after a confirmed generation change, distinguishing failure from emptiness. */
    private sealed interface ClipboardReadOutcome {
        data class Text(val text: String) : ClipboardReadOutcome
        data object Empty : ClipboardReadOutcome
        data class Failed(val cause: Throwable) : ClipboardReadOutcome
    }

    private fun readConfirmedText(): ClipboardReadOutcome =
        runCatching { clipboard.readText() }.fold(
            onSuccess = { raw ->
                val text = raw?.trim()?.takeIf { it.isNotEmpty() }
                if (text != null) ClipboardReadOutcome.Text(text) else ClipboardReadOutcome.Empty
            },
            onFailure = { ClipboardReadOutcome.Failed(it) }
        )

    private suspend fun restoreShielded(state: ClipboardSnapshotResult): Boolean =
        withContext(NonCancellable) { restoreWithRetry(state) }

    /**
     * Returns true when the original state is back on the clipboard. A null restore of a
     * [ClipboardSnapshotResult.Failed] state can never happen (the flow aborts before Copy),
     * so every state reaching here has a defined restoration.
     */
    private suspend fun restoreWithRetry(state: ClipboardSnapshotResult): Boolean {
        var attempt = 0
        while (true) {
            attempt++
            val failure = runCatching { clipboard.restore(state) }.exceptionOrNull()
            if (failure == null) return true
            // Only a busy clipboard is worth retrying; anything else fails fast.
            if (failure !is IllegalStateException || attempt >= restoreMaxAttempts) {
                logger.warn("Failed to restore clipboard after selection capture: ${failure.message}")
                return false
            }
            delay(restoreRetryDelayMs)
        }
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 20L
        const val DEFAULT_CAPTURE_TIMEOUT_MS = 600L
        const val DEFAULT_RESTORE_MAX_ATTEMPTS = 3
        const val DEFAULT_RESTORE_RETRY_DELAY_MS = 50L
    }
}
