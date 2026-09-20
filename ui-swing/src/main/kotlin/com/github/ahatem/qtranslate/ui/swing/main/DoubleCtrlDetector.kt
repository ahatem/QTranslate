package com.github.ahatem.qtranslate.ui.swing.main

/**
 * Double-Ctrl tap-pair detection as a pure state machine over Control press/release events.
 *
 * The load-bearing invariant: a Control release counts as a tap only if a corresponding
 * Control press was observed first. Orphan or synthetic releases (no observed press) are
 * ignored completely: they do not count as taps, do not arm or complete a pair, and do not
 * touch the recorded tap time. This is what keeps synthetic key-up traffic, such as a
 * clipboard manager releasing modifiers around a paste, from being read as Double Ctrl.
 *
 * A repeated Control press while Control is already held is likewise ignored, so auto-repeat
 * or duplicate down events cannot erase an in-progress combination and turn a shortcut into
 * a tap. Any non-Control key pressed while Control is held still invalidates that tap.
 *
 * Arming and firing only happen while [active] is true; tracking itself is unconditional,
 * so enabling or disabling the hotkey cannot desynchronize the press/release bookkeeping.
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
     * Deliberately not a `0L` sentinel: timestamps are monotonic microseconds-since-runtime-
     * start, so a real event can legitimately land at or near zero. With `0L` meaning both
     * "no previous tap" and "a tap at the origin", the very first tap of a session could read
     * as completing a pair.
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

    /**
     * Forgets all tap state, including an armed first tap. Called when Double Ctrl stops
     * being able to fire (global hotkeys disabled), so a tap from before the disable can
     * never pair with a tap after re-enable into a phantom double.
     */
    fun reset() {
        down = false
        pressObserved = false
        partOfCombination = false
        lastTapMs = null
    }

    /**
     * Returns true when this release completes a tap pair. Returns false for orphan releases,
     * combination releases, single taps (which only arm), pairs outside the threshold, and any
     * release while [active] is false.
     */
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
