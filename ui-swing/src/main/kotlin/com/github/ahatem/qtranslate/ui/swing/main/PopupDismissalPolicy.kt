package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import java.awt.Point
import java.awt.Rectangle

/**
 * Decides which floating popups an outside click dismisses.
 *
 * Driven by the native hook rather than by an AWT listener. The click that dismisses a
 * popup almost always lands in another application — the document being read — and AWT
 * never sees those: it only delivers events destined for this program's own windows. An
 * AWT-based version of this appeared to work when clicking on QTranslate itself and did
 * nothing at all in the case that matters.
 *
 * Pinned popups are left alone, which is the point of pinning. The live window is read
 * lazily through [Popup.window], only once the state flags say the popup matters, so a
 * popup the state says is closed is never constructed to be asked about.
 */
internal object PopupDismissalPolicy {

    /** A live read of one popup's window, taken only when it is needed. */
    data class WindowSnapshot(val visible: Boolean, val bounds: () -> Rectangle)

    /**
     * One dismissible popup.
     *
     * @param visibleInState whether the MVI state says the popup is open.
     * @param pinned whether pinning keeps it open regardless of clicks.
     * @param window supplies the on-screen window's visibility and bounds.
     * @param hideIntent the intent that closes it.
     */
    data class Popup(
        val visibleInState: Boolean,
        val pinned: Boolean,
        val window: () -> WindowSnapshot,
        val hideIntent: MainIntent,
    )

    /**
     * The hide intents for every popup [screenPoint] landed outside of.
     *
     * Mirrors the frame's original short-circuit order: a popup the state says is closed or
     * pinned is skipped before its window is ever touched, and a window that is not showing
     * is never dismissed.
     */
    fun dismissals(
        closePopupsOnClickOutside: Boolean,
        screenPoint: Point,
        popups: List<Popup>,
    ): List<MainIntent> {
        if (!closePopupsOnClickOutside) return emptyList()
        return popups.filter { popup ->
            popup.visibleInState &&
                !popup.pinned &&
                popup.window().let { snapshot ->
                    snapshot.visible && !snapshot.bounds().contains(screenPoint)
                }
        }.map { it.hideIntent }
    }
}
