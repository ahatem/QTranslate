package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotkeyDraftOperationsTest {

    private val customShowMain = Configuration.DEFAULT.copy(
        hotkeys = Configuration.DEFAULT.hotkeys.map {
            if (it.action == HotkeyAction.SHOW_MAIN_WINDOW) {
                it.copy(
                    keyCode = KeyEvent.VK_M,
                    modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK,
                    isDoubleCtrlEnabled = true
                )
            } else it
        }
    )

    @Test
    fun `show main shortcut and double ctrl are independent`() {
        val disabled = HotkeyDraftOperations.setDoubleCtrl(customShowMain, false)
        val binding = showMain(disabled)

        assertEquals(KeyEvent.VK_M, binding.keyCode)
        assertEquals(InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK, binding.modifiers)
        assertFalse(binding.isDoubleCtrlEnabled)
    }

    @Test
    fun `clearing show main shortcut preserves double ctrl and global scope`() {
        val cleared = HotkeyDraftOperations.clearShowMainWindow(customShowMain)
        val binding = showMain(cleared)

        assertEquals(0, binding.keyCode)
        assertEquals(0, binding.modifiers)
        assertTrue(binding.isDoubleCtrlEnabled)
        assertEquals(HotkeyScope.GLOBAL, binding.scope)
    }

    @Test
    fun `double ctrl toggle preserves custom shortcut`() {
        val toggled = HotkeyDraftOperations.setDoubleCtrl(customShowMain, false)
        val binding = showMain(toggled)

        assertEquals(KeyEvent.VK_M, binding.keyCode)
        assertEquals(InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK, binding.modifiers)
        assertFalse(binding.isDoubleCtrlEnabled)
    }

    @Test
    fun `show main scope cannot be toggled`() {
        val unchanged = HotkeyDraftOperations.toggleScope(customShowMain, HotkeyAction.SHOW_MAIN_WINDOW)
        assertEquals(customShowMain, unchanged)
        assertEquals(HotkeyScope.GLOBAL, showMain(unchanged).scope)
    }

    @Test
    fun `normal hotkey scope toggles without changing its shortcut`() {
        val action = HotkeyAction.CYCLE_TARGET_LANGUAGE
        val before = customShowMain.hotkeys.first { it.action == action }
        val toggled = HotkeyDraftOperations.toggleScope(customShowMain, action)
        val after = toggled.hotkeys.first { it.action == action }

        assertEquals(HotkeyScope.GLOBAL, after.scope)
        assertEquals(before.keyCode, after.keyCode)
        assertEquals(before.modifiers, after.modifiers)
    }

    private fun showMain(configuration: Configuration) =
        configuration.hotkeys.first { it.action == HotkeyAction.SHOW_MAIN_WINDOW }
}
