package com.github.ahatem.qtranslate.ui.swing.shared.clipboard

import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Captures the selected text by synthesizing Copy, without leaving any internal content on
 * the user's clipboard.
 *
 * The clipboard is snapshotted before the copy and restored before [capture]'s callback runs,
 * so clipboard managers never observe a placeholder and the QTranslate action sees the
 * clipboard as the user left it.
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
) {

    /**
     * Runs one capture. [callback] receives the copied text, or an empty string when nothing
     * was captured, and is always invoked after the clipboard has been restored.
     */
    suspend fun capture(callback: (String) -> Unit) {
        mutex.withLock {
            val snapshot = runCatching { clipboard.snapshot() }
                .onFailure { logger.warn("Clipboard snapshot failed: ${it.message}") }
                .getOrNull()

            val text = try {
                // Hotkeys can arrive while the triggering keys are still held; give the
                // originating sequence time to finish before synthesizing Copy.
                delay(preCopyDelayMs)
                copyAndRead()
            } catch (cancelled: CancellationException) {
                restore(snapshot)
                throw cancelled
            } catch (e: Exception) {
                logger.warn("Selection capture failed: ${e.message}")
                null
            }

            restore(snapshot)
            callback(text.orEmpty())
        }
    }

    private suspend fun copyAndRead(): String? {
        val token = changeMonitor.mark()
        simulateCopy()

        // No generation counter: wait a bounded settle period and take whatever is there.
        if (token == null) {
            delay(captureTimeoutMs)
            return readText()
        }

        var waited = 0L
        while (true) {
            if (changeMonitor.hasChangedSince(token)) {
                readText()?.let { return it }
            }
            if (waited >= captureTimeoutMs) return null
            delay(pollIntervalMs)
            waited += pollIntervalMs
        }
    }

    private fun readText(): String? =
        runCatching { clipboard.readText() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun restore(snapshot: ClipboardSnapshot?) {
        if (snapshot == null) return
        runCatching { clipboard.restore(snapshot) }
            .onFailure { logger.warn("Failed to restore clipboard after selection capture: ${it.message}") }
    }

    private companion object {
        const val DEFAULT_PRE_COPY_DELAY_MS = 80L
        const val DEFAULT_POLL_INTERVAL_MS = 20L
        const val DEFAULT_CAPTURE_TIMEOUT_MS = 600L
    }
}
