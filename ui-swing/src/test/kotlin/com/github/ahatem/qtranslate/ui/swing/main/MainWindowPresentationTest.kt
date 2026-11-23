package com.github.ahatem.qtranslate.ui.swing.main

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A frame cannot be built without a display, so the startup and restore decisions are driven
 * through [MainWindowPresentation] with a recording window. Escape-to-hide is covered by
 * [MainWindowEscapeBindingTest].
 */
class MainWindowPresentationTest {

    private val calls = mutableListOf<String>()

    private val presentation = MainWindowPresentation(
        setVisible = { calls += "visible=$it" },
        restoreNormalState = { calls += "normal" },
        toFront = { calls += "front" },
        focusInput = { calls += "focus" },
    )

    @Test
    fun `a normal launch shows the window`() {
        presentation.presentAtStartup(initiallyHidden = false)

        assertEquals(listOf("visible=true"), calls)
    }

    @Test
    fun `a startup launch never shows the window, not even briefly`() {
        presentation.presentAtStartup(initiallyHidden = true)

        assertEquals(listOf("visible=false"), calls)
    }

    @Test
    fun `restoring shows, unminimizes, raises and focuses the input in that order`() {
        presentation.presentAtStartup(initiallyHidden = true)
        calls.clear()

        presentation.showAndFocus()

        assertEquals(listOf("visible=true", "normal", "front", "focus"), calls)
    }
}
