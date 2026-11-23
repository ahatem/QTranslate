package com.github.ahatem.qtranslate.core.main.mvi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The lookup dock is one region showing one tool at a time. */
class LookupDockStateTest {

    @Test
    fun `opening on a tool shows exactly that tool`() {
        val images = MainState().withLookupDockOn(LookupTool.IMAGES)
        assertTrue(images.isLookupDockOpen)
        assertTrue(images.isImagesDockVisible)
        assertFalse(images.isDictionaryPanelVisible)

        val dictionary = images.withLookupDockOn(LookupTool.DICTIONARY)
        assertTrue(dictionary.isDictionaryPanelVisible)
        assertFalse(dictionary.isImagesDockVisible)
        assertTrue(dictionary.isLookupDockOpen, "switching tools never closes the dock")
    }

    @Test
    fun `closing keeps which tool was showing so it reopens where it was`() {
        val closed = MainState().withLookupDockOn(LookupTool.IMAGES).copy(isLookupDockOpen = false)
        assertFalse(closed.isImagesDockVisible)
        assertEquals(LookupTool.IMAGES, closed.lookupDockTool)
        assertTrue(closed.withLookupDockOn(closed.lookupDockTool).isImagesDockVisible)
    }
}
