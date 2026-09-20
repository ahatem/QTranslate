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
 * Trigger release synchronization lives with the caller; this class performs no settle delay
 * of its own. A snapshot that cannot be acquired aborts before Copy is synthesized, since
 * overwriting unrestorable clipboard state is worse than capturing nothing. Concurrent
 * requests are serialized through [mutex].
 *
 * See [CaptureResult] for why a clipboard change can never be attributed to this specific
 * Copy attempt.
 */
class SelectionCapture(
    private val clipboard: SystemClipboard,
    private val changeMonitor: ClipboardChangeMonitor,
    /**
     * Runs one Copy attempt. `false` means Copy definitely never went through; the clipboard
     * change observation below remains the actual completion signal either way.
     */
    private val simulateCopy: suspend () -> Boolean,
    private val logger: Logger,
    private val mutex: Mutex = Mutex(),
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val captureTimeoutMs: Long = DEFAULT_CAPTURE_TIMEOUT_MS,
    private val restoreMaxAttempts: Int = DEFAULT_RESTORE_MAX_ATTEMPTS,
    private val restoreRetryDelayMs: Long = DEFAULT_RESTORE_RETRY_DELAY_MS,
) {

    /** Runs one capture and reports the outcome via [callback]. */
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
                // Nothing to restore yet: abort without touching the clipboard.
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
            // Copy definitely never went through; polling would learn nothing.
            logger.warn("Copy injection failed; selection capture inconclusive")
            return CopyOutcome.InjectionFailed
        }

        var waited = 0L
        while (true) {
            // Forces delayed-rendering sources to actually render; the outcome is discarded
            // since nothing is confirmed changed yet.
            runCatching { clipboard.readText() }
            if (changeMonitor.hasChangedSince(token)) {
                // Only now does a read failure mean the capture failed, not that there was
                // nothing to read.
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
                // No confirmed change is not proof nothing was selected.
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

    /** Every state reaching here has a defined restoration: capture aborts before Copy on a failed snapshot. */
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
