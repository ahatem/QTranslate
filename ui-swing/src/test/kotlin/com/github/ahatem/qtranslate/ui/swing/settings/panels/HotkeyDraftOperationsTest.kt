package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.data.HotkeyPresetKind
import com.github.ahatem.qtranslate.core.settings.data.HotkeyPresets
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotkeyDraftOperationsTest {

    @Test
    fun `legacy and modern presets identify independently of action order`() {
        assertEquals(HotkeyPresetKind.LEGACY, HotkeyPresets.identify(HotkeyBinding.DEFAULTS))
        assertEquals(HotkeyPresetKind.LEGACY, HotkeyPresets.identify(HotkeyPresets.LEGACY.reversed()))
        assertEquals(HotkeyPresetKind.MODERN, HotkeyPresets.identify(HotkeyPresets.MODERN.shuffled()))
    }

    @Test
    fun `modern preset uses one useful global shortcut and preserves local bindings`() {
        val unboundGlobalActions = setOf(
            HotkeyAction.LISTEN_TO_TEXT,
            HotkeyAction.OPEN_OCR,
            HotkeyAction.REPLACE_WITH_TRANSLATION,
            HotkeyAction.SHOW_DICTIONARY,
            HotkeyAction.SHOW_IMAGES,
        )

        val quickTranslate = HotkeyPresets.MODERN.first { it.action == HotkeyAction.SHOW_QUICK_TRANSLATE }
        assertEquals(KeyEvent.VK_SEMICOLON, quickTranslate.keyCode)
        assertEquals(InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK, quickTranslate.modifiers)
        assertEquals(HotkeyScope.GLOBAL, quickTranslate.scope)

        HotkeyPresets.MODERN.forEach { binding ->
            val legacy = HotkeyPresets.LEGACY.first { it.action == binding.action }
            if (binding.action in unboundGlobalActions) {
                assertEquals(0, binding.keyCode)
                assertEquals(0, binding.modifiers)
                assertEquals(legacy.scope, binding.scope)
            } else if (binding.action != HotkeyAction.SHOW_QUICK_TRANSLATE) {
                assertEquals(legacy, binding)
            }
        }

        assertTrue(HotkeyPresets.MODERN.first { it.action == HotkeyAction.SHOW_MAIN_WINDOW }.isDoubleCtrlEnabled)
    }

    @Test
    fun `modern has no alt modifier defaults`() {
        assertTrue(HotkeyPresets.MODERN
            .filter { it.scope == HotkeyScope.GLOBAL }
            .none { it.modifiers and InputEvent.ALT_DOWN_MASK != 0 })
    }

    @Test
    fun `modified or incomplete preset is custom`() {
        val modifiedLegacy = HotkeyPresets.LEGACY.map {
            if (it.action == HotkeyAction.SHOW_QUICK_TRANSLATE) it.copy(keyCode = KeyEvent.VK_Z) else it
        }
        val modifiedModern = HotkeyPresets.MODERN.map {
            if (it.action == HotkeyAction.SHOW_QUICK_TRANSLATE) it.copy(keyCode = KeyEvent.VK_Z) else it
        }

        assertEquals(HotkeyPresetKind.CUSTOM, HotkeyPresets.identify(modifiedLegacy))
        assertEquals(HotkeyPresetKind.CUSTOM, HotkeyPresets.identify(modifiedModern))
        assertEquals(HotkeyPresetKind.CUSTOM, HotkeyPresets.identify(
            HotkeyPresets.LEGACY.filterNot { it.action == HotkeyAction.SHOW_IMAGES }
        ))
        assertEquals(HotkeyPresetKind.CUSTOM, HotkeyPresets.identify(
            HotkeyPresets.LEGACY + HotkeyPresets.LEGACY.first()
        ))
        assertEquals(HotkeyPresetKind.CUSTOM, HotkeyPresets.identify(
            HotkeyPresets.MODERN.map {
                if (it.action == HotkeyAction.CYCLE_TARGET_LANGUAGE) it.copy(scope = HotkeyScope.GLOBAL) else it
            }
        ))
        assertEquals(HotkeyPresetKind.CUSTOM, HotkeyPresets.identify(
            HotkeyPresets.MODERN.map {
                if (it.action == HotkeyAction.TRANSLATE) it.copy(keyCode = KeyEvent.VK_Z) else it
            }
        ))
    }

    @Test
    fun `double ctrl and show main shortcut changes are custom`() {
        val doubleCtrlOff = HotkeyPresets.LEGACY.map {
            if (it.action == HotkeyAction.SHOW_MAIN_WINDOW) it.copy(isDoubleCtrlEnabled = false) else it
        }
        val customShowShortcut = HotkeyPresets.LEGACY.map {
            if (it.action == HotkeyAction.SHOW_MAIN_WINDOW) it.copy(keyCode = KeyEvent.VK_M) else it
        }

        assertEquals(HotkeyPresetKind.CUSTOM, HotkeyPresets.identify(doubleCtrlOff))
        assertEquals(HotkeyPresetKind.CUSTOM, HotkeyPresets.identify(customShowShortcut))
    }

    @Test
    fun `selecting a preset replaces only the draft hotkeys`() {
        val original = Configuration.DEFAULT.copy(hotkeys = HotkeyPresets.LEGACY)
        val modernDraft = HotkeyDraftOperations.replacePreset(original, HotkeyPresetKind.MODERN)
        val legacyDraft = HotkeyDraftOperations.replacePreset(modernDraft, HotkeyPresetKind.LEGACY)

        assertEquals(HotkeyPresets.MODERN, modernDraft.hotkeys)
        assertEquals(HotkeyPresets.LEGACY, legacyDraft.hotkeys)
        assertEquals(HotkeyPresets.LEGACY, original.hotkeys)
    }

    @Test
    fun `custom selection does not fabricate bindings`() {
        val configuration = Configuration.DEFAULT.copy(hotkeys = HotkeyPresets.MODERN)
        val custom = HotkeyDraftOperations.replacePreset(configuration, HotkeyPresetKind.CUSTOM)
        assertTrue(custom.hotkeys.all { it.keyCode == 0 && it.modifiers == 0 })
        assertTrue(custom.hotkeys.none { it.isDoubleCtrlEnabled })
    }

    @Test
    fun `explicit custom clears assignments but preserves actions and scopes`() {
        val legacy = Configuration.DEFAULT.copy(hotkeys = HotkeyPresets.LEGACY)
        val custom = HotkeyDraftOperations.replacePreset(legacy, HotkeyPresetKind.CUSTOM)

        assertEquals(legacy.hotkeys.map { it.action }, custom.hotkeys.map { it.action })
        custom.hotkeys.forEach { binding ->
            val original = legacy.hotkeys.first { it.action == binding.action }
            assertEquals(0, binding.keyCode)
            assertEquals(0, binding.modifiers)
            assertEquals(original.scope, binding.scope)
            assertFalse(binding.isDoubleCtrlEnabled)
        }
        assertEquals(HotkeyPresetKind.CUSTOM, HotkeyPresets.identify(custom.hotkeys))
    }

    @Test
    fun `explicit custom clears modern assignments too`() {
        val modern = Configuration.DEFAULT.copy(hotkeys = HotkeyPresets.MODERN)
        val custom = HotkeyDraftOperations.clearForCustom(modern)

        assertEquals(modern.hotkeys.map { it.action }, custom.hotkeys.map { it.action })
        assertTrue(custom.hotkeys.all { it.keyCode == 0 && it.modifiers == 0 })
        assertTrue(custom.hotkeys.none { it.isDoubleCtrlEnabled })
        assertEquals(HotkeyPresetKind.CUSTOM, HotkeyPresets.identify(custom.hotkeys))
    }

    @Test
    fun `editing a preset changes only the edited binding`() {
        listOf(HotkeyPresets.LEGACY, HotkeyPresets.MODERN).forEach { preset ->
            val configuration = Configuration.DEFAULT.copy(hotkeys = preset)
            val edited = HotkeyBinding(HotkeyAction.OPEN_OCR, KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK)
            val custom = HotkeyDraftOperations.replaceBinding(configuration, edited)

            assertEquals(HotkeyPresetKind.CUSTOM, HotkeyPresets.identify(custom.hotkeys))
            custom.hotkeys.filter { it.action != edited.action }.forEach { binding ->
                assertEquals(preset.first { it.action == binding.action }, binding)
            }
        }
    }

    @Test
    fun `selecting either preset restores its exact bindings after custom`() {
        val custom = HotkeyDraftOperations.clearForCustom(Configuration.DEFAULT)
        assertEquals(
            HotkeyPresets.LEGACY,
            HotkeyDraftOperations.replacePreset(custom, HotkeyPresetKind.LEGACY).hotkeys
        )
        assertEquals(
            HotkeyPresets.MODERN,
            HotkeyDraftOperations.replacePreset(custom, HotkeyPresetKind.MODERN).hotkeys
        )
    }

    @Test
    fun `unbound modern action assigned later becomes custom`() {
        val configured = HotkeyDraftOperations.replaceBinding(
            Configuration.DEFAULT.copy(hotkeys = HotkeyPresets.MODERN),
            HotkeyBinding(HotkeyAction.SHOW_DICTIONARY, KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK)
        )
        assertEquals(HotkeyPresetKind.CUSTOM, HotkeyPresets.identify(configured.hotkeys))
    }

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
