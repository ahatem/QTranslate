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
        assertEquals(HotkeyPresetKind.LEGACY, HotkeyPresets.identify(HotkeyBinding.LEGACY_DEFAULTS))
        assertEquals(HotkeyPresetKind.LEGACY, HotkeyPresets.identify(HotkeyPresets.LEGACY.reversed()))
        assertEquals(HotkeyPresetKind.MODERN, HotkeyPresets.identify(HotkeyPresets.MODERN.shuffled()))
    }

    @Test
    fun `modern preset uses the complete mnemonic global shortcut set`() {
        val expectedKeys = mapOf(
            HotkeyAction.SHOW_QUICK_TRANSLATE to KeyEvent.VK_Q,
            HotkeyAction.LISTEN_TO_TEXT to KeyEvent.VK_L,
            HotkeyAction.OPEN_OCR to KeyEvent.VK_O,
            HotkeyAction.REPLACE_WITH_TRANSLATION to KeyEvent.VK_R,
            HotkeyAction.SHOW_DICTIONARY to KeyEvent.VK_D,
            HotkeyAction.SHOW_IMAGES to KeyEvent.VK_I,
        )
        val modernGlobal = HotkeyPresets.MODERN.filter { it.scope == HotkeyScope.GLOBAL }

        expectedKeys.forEach { (action, keyCode) ->
            val binding = modernGlobal.first { it.action == action }
            assertEquals(keyCode, binding.keyCode)
            assertEquals(InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK, binding.modifiers)
        }
        assertTrue(expectedKeys.keys.all { action -> modernGlobal.first { it.action == action }.hasBinding })
        assertTrue(HotkeyPresets.MODERN.first { it.action == HotkeyAction.SHOW_MAIN_WINDOW }.isDoubleCtrlEnabled)
        // Every LOCAL binding matches Legacy except TRANSLATE_DOCUMENT: Modern's GLOBAL
        // SHOW_DICTIONARY override above claims Ctrl+Shift+D, which Legacy's LOCAL
        // TRANSLATE_DOCUMENT binding already used, so Modern moves it to Ctrl+Shift+F to avoid
        // shipping the same accelerator on two actions (see HotkeyPresetsTest for the collision
        // check across every preset).
        assertTrue(HotkeyPresets.MODERN
            .filter { it.scope == HotkeyScope.LOCAL && it.action != HotkeyAction.TRANSLATE_DOCUMENT }
            .all { binding -> binding == HotkeyPresets.LEGACY.first { it.action == binding.action } })
        val modernDocumentTranslate = HotkeyPresets.MODERN.first { it.action == HotkeyAction.TRANSLATE_DOCUMENT }
        assertEquals(HotkeyScope.LOCAL, modernDocumentTranslate.scope)
        assertEquals(KeyEvent.VK_F, modernDocumentTranslate.keyCode)
        assertEquals(InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK, modernDocumentTranslate.modifiers)
    }

    @Test
    fun `modern global defaults do not use alt`() {
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
    fun `explicit custom clears modern assignments`() {
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
    fun `modifying a modern action becomes custom`() {
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
    fun `capture action scope cannot be toggled`() {
        val capture = HotkeyBinding(
            action = HotkeyAction.OCR_ACTION_COPY_TEXT,
            keyCode = KeyEvent.VK_C,
            modifiers = InputEvent.CTRL_DOWN_MASK,
            scope = HotkeyScope.CAPTURE,
        )
        val config = Configuration.DEFAULT.copy(
            ocrHotkeys = Configuration.DEFAULT.ocrHotkeys.filterNot { it.action == capture.action } + capture,
        )

        val unchanged = HotkeyDraftOperations.toggleScope(config, capture.action)

        assertEquals(config, unchanged)
        assertEquals(
            HotkeyScope.CAPTURE,
            unchanged.effectiveHotkeys.single { it.action == capture.action }.scope,
        )
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

    // ---- Batch-3 writing selection actions ----

    @Test
    fun `assigning a rewrite shortcut updates writingHotkeys, never legacy hotkeys`() {
        val rewrite = HotkeyBinding(
            action = HotkeyAction.REPLACE_WITH_REWRITE,
            keyCode = KeyEvent.VK_R,
            modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK,
        )
        val configured = HotkeyDraftOperations.replaceBinding(Configuration.DEFAULT, rewrite)

        assertEquals(rewrite, configured.writingHotkeys.single { it.action == rewrite.action })
        assertEquals(Configuration.DEFAULT.hotkeys, configured.hotkeys)
        assertEquals(Configuration.DEFAULT.ocrHotkeys, configured.ocrHotkeys)
        assertTrue(configured.hotkeys.none { it.action in HotkeyBinding.WRITING_ACTIONS })
    }

    @Test
    fun `assigning a summary shortcut updates writingHotkeys, never legacy hotkeys`() {
        val summary = HotkeyBinding(
            action = HotkeyAction.REPLACE_WITH_SUMMARY,
            keyCode = KeyEvent.VK_S,
            modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK,
        )
        val configured = HotkeyDraftOperations.replaceBinding(Configuration.DEFAULT, summary)

        assertEquals(summary, configured.writingHotkeys.single { it.action == summary.action })
        assertEquals(Configuration.DEFAULT.hotkeys, configured.hotkeys)
        assertTrue(configured.hotkeys.none { it.action in HotkeyBinding.WRITING_ACTIONS })
    }

    @Test
    fun `clearing a writing shortcut stays in writingHotkeys`() {
        val assigned = HotkeyDraftOperations.replaceBinding(
            Configuration.DEFAULT,
            HotkeyBinding(HotkeyAction.REPLACE_WITH_SUMMARY, KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK),
        )
        val current = assigned.writingHotkeys.single { it.action == HotkeyAction.REPLACE_WITH_SUMMARY }
        val cleared = HotkeyDraftOperations.replaceBinding(
            assigned,
            current.copy(keyCode = 0, modifiers = 0),
        )

        assertFalse(cleared.writingHotkeys.single { it.action == HotkeyAction.REPLACE_WITH_SUMMARY }.hasBinding)
        assertEquals(assigned.hotkeys, cleared.hotkeys)
        assertEquals(assigned.ocrHotkeys, cleared.ocrHotkeys)
        assertTrue(cleared.hotkeys.none { it.action in HotkeyBinding.WRITING_ACTIONS })
    }

    @Test
    fun `writing scope toggle stays in writingHotkeys`() {
        val assigned = HotkeyDraftOperations.replaceBinding(
            Configuration.DEFAULT,
            HotkeyBinding(
                HotkeyAction.REPLACE_WITH_REWRITE,
                KeyEvent.VK_R,
                InputEvent.CTRL_DOWN_MASK,
                scope = HotkeyScope.GLOBAL,
            ),
        )
        val toggled = HotkeyDraftOperations.toggleScope(assigned, HotkeyAction.REPLACE_WITH_REWRITE)
        val binding = toggled.writingHotkeys.single { it.action == HotkeyAction.REPLACE_WITH_REWRITE }

        assertEquals(HotkeyScope.LOCAL, binding.scope)
        assertEquals(KeyEvent.VK_R, binding.keyCode)
        assertEquals(assigned.hotkeys, toggled.hotkeys)
        assertTrue(toggled.hotkeys.none { it.action in HotkeyBinding.WRITING_ACTIONS })
    }

    @Test
    fun `custom reset clears writing rows without moving them into legacy hotkeys`() {
        val assigned = HotkeyDraftOperations.replaceBinding(
            Configuration.DEFAULT,
            HotkeyBinding(HotkeyAction.REPLACE_WITH_REWRITE, KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK),
        )
        val custom = HotkeyDraftOperations.clearForCustom(assigned)

        assertTrue(custom.writingHotkeys.all { !it.hasBinding })
        assertEquals(HotkeyBinding.WRITING_ACTIONS, custom.writingHotkeys.map { it.action }.toSet())
        assertTrue(custom.hotkeys.none { it.action in HotkeyBinding.WRITING_ACTIONS })
    }

    private fun showMain(configuration: Configuration) =
        configuration.hotkeys.first { it.action == HotkeyAction.SHOW_MAIN_WINDOW }
}
