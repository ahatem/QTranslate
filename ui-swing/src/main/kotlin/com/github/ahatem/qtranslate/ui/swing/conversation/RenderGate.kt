package com.github.ahatem.qtranslate.ui.swing.conversation

/**
 * Decides whether transcript DOM updates should be applied immediately or buffered while the user
 * is reading, hovering inside the transcript viewport, selecting text, or using the hover card.
 */
class RenderGate<T>(
    val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val DEFAULT_GRACE_MILLIS: Long = 900L
    }

    var pointerInside: Boolean = false
        private set

    private var pointerExitedAtMillis: Long? = null

    var popupVisible: Boolean = false
    var hasSelection: Boolean = false

    var pendingCount: Int = 0
        private set

    var bufferedValue: T? = null
        private set

    private var hasBuffered: Boolean = false
    private var lastRenderedValue: T? = null
    private var hasLastRendered: Boolean = false

    fun onPointerEntered() {
        pointerInside = true
        pointerExitedAtMillis = null
    }

    fun onPointerExited(now: Long = clock()) {
        if (pointerInside) {
            pointerInside = false
            pointerExitedAtMillis = now
        }
    }

    fun isWithinGracePeriod(now: Long = clock()): Boolean {
        if (pointerInside) return false
        val exitedAt = pointerExitedAtMillis ?: return false
        return (now - exitedAt) in 0 until graceMillis
    }

    fun isFrozen(now: Long = clock()): Boolean =
        pointerInside || isWithinGracePeriod(now) || popupVisible || hasSelection

    /**
     * Submits a candidate state to render.
     * Returns true if the caller should render [value] immediately, or false if it was buffered.
     */
    fun submit(value: T, now: Long = clock()): Boolean {
        if (isFrozen(now)) {
            val baseline = if (hasBuffered) bufferedValue else lastRenderedValue
            val changed = (!hasBuffered && !hasLastRendered) || value != baseline
            if (changed) {
                bufferedValue = value
                hasBuffered = true
                pendingCount++
            }
            return false
        }
        bufferedValue = null
        hasBuffered = false
        pendingCount = 0
        lastRenderedValue = value
        hasLastRendered = true
        return true
    }

    /**
     * Flushes and returns the buffered value if the gate is no longer frozen; otherwise returns null.
     */
    fun tryFlushIfUnfrozen(now: Long = clock()): T? {
        if (isFrozen(now) || !hasBuffered) return null
        return forceFlush()
    }

    /**
     * Unconditionally flushes and returns the buffered value (e.g. when the user clicks the pause pill).
     */
    fun forceFlush(): T? {
        if (!hasBuffered) return null
        val value = bufferedValue
        bufferedValue = null
        hasBuffered = false
        pendingCount = 0
        lastRenderedValue = value
        hasLastRendered = true
        return value
    }

    fun hasPendingUpdates(): Boolean = hasBuffered && pendingCount > 0
}
