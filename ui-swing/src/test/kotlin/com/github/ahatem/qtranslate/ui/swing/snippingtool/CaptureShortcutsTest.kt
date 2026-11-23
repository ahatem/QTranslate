package com.github.ahatem.qtranslate.ui.swing.snippingtool

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureShortcutsTest {

    @Test
    fun `only capture-scope bindings are projected`() {
        val bindings = listOf(
            binding(HotkeyAction.OCR_ACTION_COPY_TEXT, KeyEvent.VK_C),
            binding(HotkeyAction.OPEN_OCR, KeyEvent.VK_I, scope = HotkeyScope.GLOBAL),
            binding(HotkeyAction.COPY_TRANSLATION, KeyEvent.VK_C, scope = HotkeyScope.LOCAL),
        )

        val projected = captureShortcutBindings(bindings)

        assertEquals(setOf(HotkeyAction.OCR_ACTION_COPY_TEXT), projected.keys)
    }

    @Test
    fun `disabled and unassigned capture actions are not installed`() {
        val bindings = listOf(
            binding(HotkeyAction.OCR_ACTION_SAVE_IMAGE, 0),
            binding(HotkeyAction.OCR_ACTION_RECROP, KeyEvent.VK_R, enabled = false),
        )

        assertTrue(installableCaptureShortcuts(bindings).isEmpty())
    }

    @Test
    fun `bare enter and escape are reserved, modified ones are not`() {
        class Case(val label: String, val action: HotkeyAction, val key: Int, val modifiers: Int, val reserved: Boolean)

        val ctrl = InputEvent.CTRL_DOWN_MASK
        val cases = listOf(
            Case("bare enter", HotkeyAction.OCR_ACTION_TRANSLATE, KeyEvent.VK_ENTER, 0, reserved = true),
            Case("bare escape", HotkeyAction.OCR_ACTION_CANCEL, KeyEvent.VK_ESCAPE, 0, reserved = true),
            Case("bare enter on another capture action", HotkeyAction.OCR_ACTION_COPY_IMAGE, KeyEvent.VK_ENTER, 0, reserved = true),
            Case("ctrl enter", HotkeyAction.OCR_ACTION_TRANSLATE, KeyEvent.VK_ENTER, ctrl, reserved = false),
            Case("ctrl escape", HotkeyAction.OCR_ACTION_CANCEL, KeyEvent.VK_ESCAPE, ctrl, reserved = false),
            Case("non-capture action keeps bare enter", HotkeyAction.TRANSLATE, KeyEvent.VK_ENTER, 0, reserved = false),
            Case("non-capture action keeps bare escape", HotkeyAction.OPEN_OCR, KeyEvent.VK_ESCAPE, 0, reserved = false),
        )
        for (case in cases) {
            assertEquals(case.reserved, isReservedBareCaptureKey(case.action, case.key, case.modifiers), case.label)
            if (case.action.name.startsWith("OCR_ACTION_")) {
                val installed = installableCaptureShortcuts(listOf(binding(case.action, case.key, modifiers = case.modifiers)))
                assertEquals(!case.reserved, installed.isNotEmpty(), "${case.label}: installed")
            }
        }
    }

    @Test
    fun `capture actions map to their toolbar actions`() {
        val bindings = listOf(
            binding(HotkeyAction.OCR_ACTION_TRANSLATE, KeyEvent.VK_T),
            binding(HotkeyAction.OCR_ACTION_COPY_TEXT, KeyEvent.VK_C),
            binding(HotkeyAction.OCR_ACTION_COPY_IMAGE, KeyEvent.VK_I),
            binding(HotkeyAction.OCR_ACTION_SAVE_IMAGE, KeyEvent.VK_S),
            binding(HotkeyAction.OCR_ACTION_RECROP, KeyEvent.VK_R),
            binding(HotkeyAction.OCR_ACTION_CANCEL, KeyEvent.VK_X),
        )

        assertEquals(
            listOf(
                CaptureToolbarAction.TRANSLATE,
                CaptureToolbarAction.COPY_TEXT,
                CaptureToolbarAction.COPY_IMAGE,
                CaptureToolbarAction.SAVE_IMAGE,
                CaptureToolbarAction.RECROP,
                CaptureToolbarAction.CANCEL,
            ),
            installableCaptureShortcuts(bindings).map { it.second }.sortedBy { it.ordinal },
        )
    }

    private fun binding(
        action: HotkeyAction,
        keyCode: Int,
        scope: HotkeyScope = HotkeyScope.CAPTURE,
        enabled: Boolean = true,
        modifiers: Int = InputEvent.CTRL_DOWN_MASK,
    ) = HotkeyBinding(
        action = action,
        keyCode = keyCode,
        modifiers = modifiers,
        isEnabled = enabled,
        scope = scope,
    )
}
