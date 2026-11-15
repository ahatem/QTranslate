package com.github.ahatem.qtranslate.ui.swing.main

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared loading marker has to appear for a replace, which has no window of its own.
 *
 * A Rewrite or Summary used to run with nothing on screen saying it was working: the marker's
 * condition required the translation form's loading flag, and an inline replace never raises it.
 */
class LoadingIndicatorVisibilityTest {

    @Test
    fun `a replace shows the marker without any popup`() {
        assertTrue(
            showsLoadingIndicator(isLoading = false, popupPending = false, isReplacing = true),
            "a replace is work in progress with no other feedback",
        )
    }

    @Test
    fun `a popup translation shows the marker while it has nothing to show`() {
        assertTrue(
            showsLoadingIndicator(isLoading = true, popupPending = true, isReplacing = false),
            "the popup is open but empty, so the wait has to be covered",
        )
    }

    @Test
    fun `an idle application shows nothing`() {
        assertFalse(
            showsLoadingIndicator(isLoading = false, popupPending = false, isReplacing = false),
        )
    }

    @Test
    fun `a main-window translation with no popup pending shows nothing`() {
        assertFalse(
            showsLoadingIndicator(isLoading = true, popupPending = false, isReplacing = false),
            "the form reports its own progress; the marker is for waits with nowhere to show",
        )
    }
}
