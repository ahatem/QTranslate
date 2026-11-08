package com.github.ahatem.qtranslate.core.workspace.translation

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A translation admitted by the canonical owner, before any work or cancellation runs.
 * OCR records this identity while it owns its claim, then executes the same canonical workflow
 * outside its lock. Only the jobs detached by admission can be cancelled by this start.
 */
class TranslationStart internal constructor(
    internal val primary: TranslationRequestOwner.Claim,
    internal val secondary: ExtraOutputWorkOwner.Claim,
) {
    internal val requestId: Long get() = primary.requestId
    private val released = AtomicBoolean()

    internal fun cancelPrevious() {
        if (!released.compareAndSet(false, true)) return
        primary.previousJob?.cancel(CancellationException("New translation requested"))
        secondary.previousJob?.cancel(CancellationException("New translation requested"))
    }
}
