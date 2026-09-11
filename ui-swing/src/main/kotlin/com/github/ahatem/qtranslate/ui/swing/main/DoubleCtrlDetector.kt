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
    private var lastTapMs = 0L

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
            lastTapMs = 0L
            return false
        }
        if (!active) return false
        return if (nowMs - lastTapMs < thresholdMs) {
            lastTapMs = 0L
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
