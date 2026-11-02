package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Outside-click popup dismissal, without real popups.
 *
 * The live window is read through a lambda so the tests can prove *when* it is read: a popup
 * the state says is closed, or one that is pinned, must never have its window touched at
 * all — the frame's popups are `lazy`, and asking one about its bounds constructs it.
 */
class PopupDismissalPolicyTest {

    private val inside = Point(100, 100)
    private val outside = Point(2000, 2000)

    private fun popup(
        visibleInState: Boolean = true,
        pinned: Boolean = false,
        windowVisible: Boolean = true,
        bounds: Rectangle = Rectangle(0, 0, 400, 300),
        hideIntent: MainIntent = MainIntent.HideQuickTranslate,
        windowReads: MutableList<String> = mutableListOf(),
        boundsReads: MutableList<String> = mutableListOf(),
    ): PopupDismissalPolicy.Popup = PopupDismissalPolicy.Popup(
        visibleInState = visibleInState,
        pinned = pinned,
        window = {
            windowReads += "read"
            PopupDismissalPolicy.WindowSnapshot(windowVisible) { boundsReads += "bounds"; bounds }
        },
        hideIntent = hideIntent,
    )

    @Test
    fun `an outside click hides an unpinned visible popup`() {
        assertEquals(
            listOf(MainIntent.HideQuickTranslate),
            PopupDismissalPolicy.dismissals(true, outside, listOf(popup())),
        )
    }

    @Test
    fun `a click inside the popup keeps it`() {
        assertEquals(
            emptyList<MainIntent>(),
            PopupDismissalPolicy.dismissals(true, inside, listOf(popup())),
        )
    }

    @Test
    fun `a hidden window is not measured`() {
        val boundsReads = mutableListOf<String>()
        PopupDismissalPolicy.dismissals(true, outside, listOf(popup(windowVisible = false, boundsReads = boundsReads)))
        assertTrue(boundsReads.isEmpty(), "a hidden window must not be asked for its bounds")
    }

    @Test
    fun `a pinned popup survives an outside click`() {
        assertEquals(
            emptyList<MainIntent>(),
            PopupDismissalPolicy.dismissals(true, outside, listOf(popup(pinned = true))),
        )
    }

    @Test
    fun `a popup the state says is closed is skipped without reading its window`() {
        val reads = mutableListOf<String>()

        assertEquals(
            emptyList<MainIntent>(),
            PopupDismissalPolicy.dismissals(true, outside, listOf(popup(visibleInState = false, windowReads = reads))),
        )
        assertTrue(reads.isEmpty(), "a state-invisible popup must not be constructed to be measured")
    }

    @Test
    fun `a pinned popup is skipped without reading its window`() {
        val reads = mutableListOf<String>()

        assertEquals(
            emptyList<MainIntent>(),
            PopupDismissalPolicy.dismissals(true, outside, listOf(popup(pinned = true, windowReads = reads))),
        )
        assertTrue(reads.isEmpty(), "pinned means never even asked")
    }

    @Test
    fun `a state-visible popup whose window is not showing is skipped`() {
        val reads = mutableListOf<String>()

        assertEquals(
            emptyList<MainIntent>(),
            PopupDismissalPolicy.dismissals(true, outside, listOf(popup(windowVisible = false, windowReads = reads))),
        )
        assertEquals(listOf("read"), reads, "the window may be read, but an unshowing window is never dismissed")
    }

    @Test
    fun `the setting turned off dismisses nothing and reads nothing`() {
        val reads = mutableListOf<String>()

        assertEquals(
            emptyList<MainIntent>(),
            PopupDismissalPolicy.dismissals(false, outside, listOf(popup(windowReads = reads))),
        )
        assertTrue(reads.isEmpty(), "no popup is consulted while the setting is off")
    }

    @Test
    fun `each popup is judged on its own pinning and position`() {
        val quickTranslate = popup(hideIntent = MainIntent.HideQuickTranslate)
        val dictionary = popup(pinned = true, hideIntent = MainIntent.HideQuickDictionary)
        val images = popup(hideIntent = MainIntent.HideImageSearch)

        assertEquals(
            listOf(MainIntent.HideQuickTranslate, MainIntent.HideImageSearch),
            PopupDismissalPolicy.dismissals(true, outside, listOf(quickTranslate, dictionary, images)),
        )
    }
}
