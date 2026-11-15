package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The recorder warns about a combination that is already taken while it is being recorded.
 *
 * The settings table can only report a conflict after the fact, which left the user to record a
 * duplicate, save it, and then read a warning. This is the same detector run against the
 * combination about to be saved, so the two cannot disagree — and it stays advisory, because a
 * deliberate duplicate is the user's decision to make.
 */
class HotkeyRecorderConflictTest {

    private val bindings = Configuration.DEFAULT.effectiveHotkeys

    private fun current(action: HotkeyAction) = bindings.find { it.action == action }

    @Test
    fun `a captured duplicate names the command that already holds it`() {
        val conflicts = HotkeyRecorderDialog.conflictingActions(
            action = HotkeyAction.LISTEN_TO_TEXT,
            keyCode = KeyEvent.VK_Q,
            modifiers = InputEvent.CTRL_DOWN_MASK,
            current = current(HotkeyAction.LISTEN_TO_TEXT),
            bindings = bindings,
        )

        assertEquals(setOf(HotkeyAction.SHOW_QUICK_TRANSLATE), conflicts)
    }

    @Test
    fun `an unused combination reports nothing`() {
        val conflicts = HotkeyRecorderDialog.conflictingActions(
            action = HotkeyAction.LISTEN_TO_TEXT,
            keyCode = KeyEvent.VK_F9,
            modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK,
            current = current(HotkeyAction.LISTEN_TO_TEXT),
            bindings = bindings,
        )

        assertTrue(conflicts.isEmpty(), "a free combination is not a conflict: $conflicts")
    }

    @Test
    fun `recording the combination a command already has is not a conflict with itself`() {
        val listen = current(HotkeyAction.LISTEN_TO_TEXT)!!
        val conflicts = HotkeyRecorderDialog.conflictingActions(
            action = HotkeyAction.LISTEN_TO_TEXT,
            keyCode = listen.keyCode,
            modifiers = listen.modifiers,
            current = listen,
            bindings = bindings,
        )

        assertTrue(conflicts.isEmpty(), "an action cannot conflict with its own binding: $conflicts")
    }

    @Test
    fun `an unbound capture reports nothing`() {
        val conflicts = HotkeyRecorderDialog.conflictingActions(
            action = HotkeyAction.REPLACE_WITH_REWRITE,
            keyCode = 0,
            modifiers = 0,
            current = current(HotkeyAction.REPLACE_WITH_REWRITE),
            bindings = bindings,
        )

        assertTrue(conflicts.isEmpty(), "there is no combination to be taken")
    }

    @Test
    fun `the conflict is judged in the scope the binding will be saved with`() {
        // Ctrl+Q is taken globally. A LOCAL binding recording the same chord collides with it,
        // and the detector says so — the scope is what decides, not the key alone.
        val listen = current(HotkeyAction.LISTEN_TO_TEXT)!!.copy(scope = com.github.ahatem.qtranslate.core.settings.data.HotkeyScope.LOCAL)
        val conflicts = HotkeyRecorderDialog.conflictingActions(
            action = HotkeyAction.LISTEN_TO_TEXT,
            keyCode = KeyEvent.VK_Q,
            modifiers = InputEvent.CTRL_DOWN_MASK,
            current = listen,
            bindings = bindings.filterNot { it.action == HotkeyAction.LISTEN_TO_TEXT } + listen,
        )

        assertEquals(setOf(HotkeyAction.SHOW_QUICK_TRANSLATE), conflicts)
    }
}
