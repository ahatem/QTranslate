package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.settings.data.CommandRegistry
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JMenuItem
import javax.swing.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Shortcut hints are derived from the user's configured bindings — never from
 * chords hardcoded in menu code — so these tests bind deliberately non-default
 * chords and assert the hints follow the configuration.
 */
class CommandShortcutHintsTest {

    private fun binding(action: HotkeyAction, keyCode: Int, modifiers: Int, enabled: Boolean = true) =
        HotkeyBinding(action, keyCode = keyCode, modifiers = modifiers, scope = HotkeyScope.LOCAL, isEnabled = enabled)

    @Test
    fun `hints format the configured chord and keystroke`() {
        val hints = CommandShortcutHints.fromBindings(
            listOf(binding(HotkeyAction.SHOW_DICTIONARY, KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK))
        )

        assertEquals("Ctrl+Alt+X", hints.chordFor(HotkeyAction.SHOW_DICTIONARY))
        assertEquals(
            KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK),
            hints.keyStrokeFor(HotkeyAction.SHOW_DICTIONARY),
        )
    }

    @Test
    fun `disabled and unbound actions show no hint`() {
        val hints = CommandShortcutHints.fromBindings(
            listOf(
                binding(HotkeyAction.SHOW_DICTIONARY, KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK, enabled = false),
                binding(HotkeyAction.SHOW_HISTORY, 0, 0),
            )
        )

        assertNull(hints.chordFor(HotkeyAction.SHOW_DICTIONARY))
        assertNull(hints.chordFor(HotkeyAction.SHOW_HISTORY))
        assertNull(hints.keyStrokeFor(HotkeyAction.SHOW_DICTIONARY))
        assertNull(hints.keyStrokeFor(HotkeyAction.SHOW_HISTORY))
    }

    @Test
    fun `actions without a binding show no hint`() {
        assertNull(CommandShortcutHints.EMPTY.chordFor(HotkeyAction.SHOW_DICTIONARY))
        assertNull(CommandShortcutHints.EMPTY.keyStrokeFor(HotkeyAction.SHOW_DICTIONARY))
    }

    @Test
    fun `menu items show the configured accelerator and tooltip`() {
        val hints = CommandShortcutHints.fromBindings(
            listOf(binding(HotkeyAction.SHOW_DICTIONARY, KeyEvent.VK_X, InputEvent.ALT_DOWN_MASK))
        )
        val item = JMenuItem("Dictionary").apply { applyShortcutHint(hints, HotkeyAction.SHOW_DICTIONARY) }

        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.ALT_DOWN_MASK), item.accelerator)
        assertEquals("Dictionary (Alt+X)", item.toolTipText)
    }

    @Test
    fun `menu items without a configured binding stay plain`() {
        val item = JMenuItem("Dictionary").apply { applyShortcutHint(CommandShortcutHints.EMPTY, HotkeyAction.SHOW_DICTIONARY) }

        assertNull(item.accelerator)
        assertNull(item.toolTipText)
    }

    @Test
    fun `every hinted action is backed by the command registry`() {
        // Drift guard: hints must not grow a parallel command vocabulary. Any
        // action with a hint comes from HotkeyAction, which CommandRegistry
        // covers 1:1 (see CommandRegistryCompletenessTest) — adding an action
        // without a spec fails there, and this fails if a hint ever references
        // something the registry does not know.
        val hints = CommandShortcutHints.fromBindings(
            listOf(binding(HotkeyAction.SHOW_DICTIONARY, KeyEvent.VK_X, InputEvent.ALT_DOWN_MASK))
        )

        assertEquals("Alt+X", hints.chordFor(HotkeyAction.SHOW_DICTIONARY))
        assertEquals(
            HotkeyAction.SHOW_DICTIONARY,
            CommandRegistry.specFor(HotkeyAction.SHOW_DICTIONARY)?.action,
        )
    }
}
