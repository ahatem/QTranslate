package com.github.ahatem.qtranslate.ui.swing.main.input

/**
 * Double-Ctrl tap-pair detection as a pure state machine over Control press/release events.
 *
 * A release counts as a tap only if a corresponding press was observed first; orphan or
 * synthetic releases (e.g. a clipboard manager releasing modifiers around a paste) are ignored
 * entirely, including for tap-timing. Tracking runs unconditionally regardless of [active], so
 * toggling the hotkey cannot desync the press/release bookkeeping.
 */
internal class DoubleCtrlDetector(
    private val thresholdMs: Long = DEFAULT_DOUBLE_CTRL_THRESHOLD_MS,
) {

    private var down = false
    private var pressObserved = false
    private var partOfCombination = false

    /**
     * When the previous tap happened, or null when none is armed.
     *
     * Not a `0L` sentinel: a real event can land at or near zero, which would make the first
     * tap of a session misread as completing a pair.
     */
    private var lastTapMs: Long? = null

    fun onControlPressed() {
        if (!down) {
            down = true
            pressObserved = true
            partOfCombination = false
        }
    }

    fun onOtherKeyPressed() {
        if (down) partOfCombination = true
    }

    /** Forgets all tap state, so a tap before a disable can't pair with one after re-enable. */
    fun reset() {
        down = false
        pressObserved = false
        partOfCombination = false
        lastTapMs = null
    }

    /** True only when this release completes a tap pair within the threshold. */
    fun onControlReleased(nowMs: Long, active: Boolean): Boolean {
        if (!down || !pressObserved) {
            down = false
            pressObserved = false
            return false
        }
        down = false
        pressObserved = false
        if (partOfCombination) {
            partOfCombination = false
            lastTapMs = null
            return false
        }
        if (!active) return false
        val previous = lastTapMs
        return if (previous != null && nowMs - previous < thresholdMs) {
            lastTapMs = null
            true
        } else {
            lastTapMs = nowMs
            false
        }
    }

    companion object {
        const val DEFAULT_DOUBLE_CTRL_THRESHOLD_MS = 400L
    }
}
