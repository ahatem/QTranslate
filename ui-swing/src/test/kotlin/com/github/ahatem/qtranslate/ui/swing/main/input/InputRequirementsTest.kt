package com.github.ahatem.qtranslate.ui.swing.main.input

import kotlin.test.Test
import kotlin.test.assertEquals

class InputRequirementsTest {

    @Test
    fun `everything on requests everything`() {
        assertEquals(
            ResolvedInput(registerHotkeys = true, keyboard = true, mouseButtons = true, mouseMotion = false),
            InputRequirements.resolve(
                hotkeysEnabled = true,
                doubleCtrlEnabled = true,
                selectionIconEnabled = false,
                dismissOnOutsideClickEnabled = true
            )
        )
    }

    @Test
    fun `disabled hotkeys clear registrations and keyboard but keep opted-in mouse`() {
        assertEquals(
            ResolvedInput(registerHotkeys = false, keyboard = false, mouseButtons = true, mouseMotion = true),
            InputRequirements.resolve(
                hotkeysEnabled = false,
                doubleCtrlEnabled = true,
                selectionIconEnabled = true,
                dismissOnOutsideClickEnabled = false
            )
        )
    }

    @Test
    fun `disabled double ctrl drops keyboard while hotkeys stay registered`() {
        assertEquals(
            ResolvedInput(registerHotkeys = true, keyboard = false, mouseButtons = true, mouseMotion = false),
            InputRequirements.resolve(
                hotkeysEnabled = true,
                doubleCtrlEnabled = false,
                selectionIconEnabled = false,
                dismissOnOutsideClickEnabled = true
            )
        )
    }

    @Test
    fun `selection off and dismissal off request no mouse observation`() {
        assertEquals(
            ResolvedInput(registerHotkeys = true, keyboard = true, mouseButtons = false, mouseMotion = false),
            InputRequirements.resolve(
                hotkeysEnabled = true,
                doubleCtrlEnabled = true,
                selectionIconEnabled = false,
                dismissOnOutsideClickEnabled = false
            )
        )
    }

    @Test
    fun `all features off requests nothing`() {
        assertEquals(
            ResolvedInput(registerHotkeys = false, keyboard = false, mouseButtons = false, mouseMotion = false),
            InputRequirements.resolve(
                hotkeysEnabled = false,
                doubleCtrlEnabled = false,
                selectionIconEnabled = false,
                dismissOnOutsideClickEnabled = false
            )
        )
    }

    @Test
    fun `selection icon alone requests buttons and motion without keyboard change`() {
        assertEquals(
            ResolvedInput(registerHotkeys = false, keyboard = false, mouseButtons = true, mouseMotion = true),
            InputRequirements.resolve(
                hotkeysEnabled = false,
                doubleCtrlEnabled = false,
                selectionIconEnabled = true,
                dismissOnOutsideClickEnabled = false
            )
        )
    }
}
