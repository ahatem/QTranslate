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
    fun `enter and escape stay reserved for the primary and cancel actions`() {
        val bindings = listOf(
            binding(HotkeyAction.OCR_ACTION_TRANSLATE, KeyEvent.VK_ENTER),
            binding(HotkeyAction.OCR_ACTION_CANCEL, KeyEvent.VK_ESCAPE),
            binding(HotkeyAction.OCR_ACTION_COPY_IMAGE, KeyEvent.VK_C),
        )

        val installed = installableCaptureShortcuts(bindings)

        assertEquals(listOf(HotkeyAction.OCR_ACTION_COPY_IMAGE), installed.map { it.first.action })
        assertEquals(CaptureToolbarAction.COPY_IMAGE, installed.single().second)
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
    ) = HotkeyBinding(
        action = action,
        keyCode = keyCode,
        modifiers = InputEvent.CTRL_DOWN_MASK,
        isEnabled = enabled,
        scope = scope,
    )
}
