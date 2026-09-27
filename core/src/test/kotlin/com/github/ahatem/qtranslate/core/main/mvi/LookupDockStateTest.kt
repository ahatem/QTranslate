package com.github.ahatem.qtranslate.core.main.mvi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The lookup dock is one region showing one tool at a time. */
class LookupDockStateTest {

    @Test
    fun `a fresh state has a closed dock that would show the dictionary`() {
        val state = MainState()
        assertFalse(state.isLookupDockOpen)
        assertEquals(LookupTool.DICTIONARY, state.lookupDockTool)
        assertFalse(state.isDictionaryPanelVisible)
        assertFalse(state.isImagesDockVisible)
    }

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
    fun `the dictionary toggle opens the dock on the dictionary and closes it again`() {
        val opened = MainState().withDictionaryPanelToggled()
        assertTrue(opened.isDictionaryPanelVisible)

        val closed = opened.withDictionaryPanelToggled()
        assertFalse(closed.isLookupDockOpen)
        assertFalse(closed.isDictionaryPanelVisible)
    }

    @Test
    fun `the dictionary toggle on an images dock switches to the dictionary instead of closing`() {
        val toggled = MainState().withLookupDockOn(LookupTool.IMAGES).withDictionaryPanelToggled()
        assertTrue(toggled.isLookupDockOpen)
        assertTrue(toggled.isDictionaryPanelVisible)
    }

    @Test
    fun `closing keeps which tool was showing so it reopens where it was`() {
        val closed = MainState().withLookupDockOn(LookupTool.IMAGES).copy(isLookupDockOpen = false)
        assertFalse(closed.isImagesDockVisible)
        assertEquals(LookupTool.IMAGES, closed.lookupDockTool)
        assertTrue(closed.withLookupDockOn(closed.lookupDockTool).isImagesDockVisible)
    }
}
