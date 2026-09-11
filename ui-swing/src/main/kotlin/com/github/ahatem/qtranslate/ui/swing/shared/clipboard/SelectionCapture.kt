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
 * The capture order is serialized capture, key-release settle delay, snapshot taken as close
 * as practical to the copy, sequence mark, Copy, capture, restore, and only then the callback,
 * so clipboard managers never observe a placeholder and the QTranslate action sees the
 * clipboard as the user left it. Keeping the snapshot-to-copy window small matters: a snapshot
 * taken before the settle delay could otherwise overwrite a legitimate clipboard update that
 * landed during the delay.
 *
 * A snapshot that cannot be acquired ([ClipboardSnapshotResult.Failed]) aborts the capture
 * before Copy is synthesized: overwriting clipboard state that cannot be restored is worse
 * than capturing nothing. Likewise an unavailable change monitor fails the capture instead
 * of accepting whatever text happens to be on the clipboard.
 *
 * Restoration is retried a bounded number of times for temporary clipboard-busy failures and
 * runs cancellation-shielded, so cancellation never strands the clipboard in the captured
 * state. The capture itself stays cancellable.
 *
 * Concurrent requests are serialized, so two hotkeys cannot fight over clipboard ownership.
 */
class SelectionCapture(
    private val clipboard: SystemClipboard,
    private val changeMonitor: ClipboardChangeMonitor,
    private val simulateCopy: suspend () -> Unit,
    private val logger: Logger,
    private val mutex: Mutex = Mutex(),
    private val preCopyDelayMs: Long = DEFAULT_PRE_COPY_DELAY_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val captureTimeoutMs: Long = DEFAULT_CAPTURE_TIMEOUT_MS,
    private val restoreMaxAttempts: Int = DEFAULT_RESTORE_MAX_ATTEMPTS,
    private val restoreRetryDelayMs: Long = DEFAULT_RESTORE_RETRY_DELAY_MS,
) {

    /**
     * Runs one capture. [callback] receives the copied text, or an empty string when nothing
     * was captured, and is always invoked after the clipboard has been restored.
     */
    suspend fun capture(callback: (String) -> Unit) {
        mutex.withLock {
            // Hotkeys can arrive while the triggering keys are still held; give the
            // originating sequence time to finish before touching the clipboard at all.
            delay(preCopyDelayMs)

            val snapshot = when (val result = readSnapshot()) {
                is ClipboardSnapshotResult.Available -> result.snapshot
                is ClipboardSnapshotResult.Empty -> null
                is ClipboardSnapshotResult.Failed -> {
                    logger.warn("Clipboard snapshot failed, skipping selection capture: ${result.cause?.message}")
                    currentCoroutineContext().ensureActive()
                    callback("")
                    return
                }
            }

            val token = changeMonitor.mark()
            if (token == null) {
                logger.warn("Clipboard change monitor unavailable, skipping selection capture")
                restoreShielded(snapshot)
                currentCoroutineContext().ensureActive()
                callback("")
                return
            }

            val text = try {
                copyAndRead(token)
            } catch (cancelled: CancellationException) {
                restoreShielded(snapshot)
                throw cancelled
            } catch (e: Exception) {
                logger.warn("Selection capture failed: ${e.message}")
                null
            }

            restoreShielded(snapshot)
            currentCoroutineContext().ensureActive()
            callback(text.orEmpty())
        }
    }

    private suspend fun copyAndRead(token: Long): String? {
        simulateCopy()

        var waited = 0L
        while (true) {
            // With delayed rendering the sequence may not advance until the clipboard data
            // is actually requested, while the source application waits for that request
            // before rendering. Reading here triggers rendering; the text is still only
            // accepted once the sequence change below verifies the copy landed. The nudged
            // read is always discarded: text equality is never used as proof of a copy.
            readText()
            if (changeMonitor.hasChangedSince(token)) {
                return readText()
            }
            if (waited >= captureTimeoutMs) return null
            delay(pollIntervalMs)
            waited += pollIntervalMs
        }
    }

    private fun readSnapshot(): ClipboardSnapshotResult =
        runCatching { clipboard.snapshot() }
            .getOrElse { ClipboardSnapshotResult.Failed(it) }

    private fun readText(): String? =
        runCatching { clipboard.readText() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun restoreShielded(snapshot: ClipboardSnapshot?) {
        withContext(NonCancellable) { restoreWithRetry(snapshot) }
    }

    private suspend fun restoreWithRetry(snapshot: ClipboardSnapshot?) {
        if (snapshot == null) return
        var attempt = 0
        while (true) {
            attempt++
            val failure = runCatching { clipboard.restore(snapshot) }.exceptionOrNull()
            if (failure == null) return
            // Only a busy clipboard is worth retrying; anything else fails fast.
            if (failure !is IllegalStateException || attempt >= restoreMaxAttempts) {
                logger.warn("Failed to restore clipboard after selection capture: ${failure.message}")
                return
            }
            delay(restoreRetryDelayMs)
        }
    }

    private companion object {
        const val DEFAULT_PRE_COPY_DELAY_MS = 80L
        const val DEFAULT_POLL_INTERVAL_MS = 20L
        const val DEFAULT_CAPTURE_TIMEOUT_MS = 600L
        const val DEFAULT_RESTORE_MAX_ATTEMPTS = 3
        const val DEFAULT_RESTORE_RETRY_DELAY_MS = 50L
    }
}
