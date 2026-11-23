package com.github.ahatem.qtranslate.ui.swing.main.input

import kotlin.test.Test
import kotlin.test.assertEquals

class InputRequirementsTest {

    private data class Case(
        val name: String,
        val hotkeys: Boolean,
        val doubleCtrl: Boolean,
        val selection: Boolean,
        val dismiss: Boolean,
        val expected: ResolvedInput,
    )

    @Test
    fun `each feature requests only the input it needs`() {
        listOf(
            Case("everything on", true, true, false, true,
                ResolvedInput(registerHotkeys = true, keyboard = true, mouseButtons = true, mouseMotion = false)),
            Case("disabled hotkeys clear registrations and keyboard but keep opted-in mouse", false, true, true, false,
                ResolvedInput(registerHotkeys = false, keyboard = false, mouseButtons = true, mouseMotion = true)),
            Case("disabled double ctrl drops keyboard while hotkeys stay registered", true, false, false, true,
                ResolvedInput(registerHotkeys = true, keyboard = false, mouseButtons = true, mouseMotion = false)),
            Case("selection off and dismissal off request no mouse observation", true, true, false, false,
                ResolvedInput(registerHotkeys = true, keyboard = true, mouseButtons = false, mouseMotion = false)),
            Case("all features off request nothing", false, false, false, false,
                ResolvedInput(registerHotkeys = false, keyboard = false, mouseButtons = false, mouseMotion = false)),
            Case("selection capture alone requests buttons and motion", false, false, true, false,
                ResolvedInput(registerHotkeys = false, keyboard = false, mouseButtons = true, mouseMotion = true)),
        ).forEach { case ->
            val state = InputRuntimeState(
                globalHotkeysEnabled = case.hotkeys,
                doubleCtrlEnabled = case.doubleCtrl,
                selectionCaptureEnabled = case.selection,
                dismissOnOutsideClickEnabled = case.dismiss,
            )
            assertEquals(case.expected, InputRequirements.resolve(state), case.name)
        }
    }
}
