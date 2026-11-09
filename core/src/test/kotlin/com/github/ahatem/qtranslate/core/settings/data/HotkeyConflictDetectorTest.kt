package com.github.ahatem.qtranslate.core.settings.data

import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Minimal conflict-detection contract (B5): duplicate enabled bindings in one scope, and
 * local/global collisions on the same combination. Disabled and unbound rows never
 * conflict, and CAPTURE bindings only collide among themselves.
 */
class HotkeyConflictDetectorTest {

    private fun binding(
        action: HotkeyAction,
        keyCode: Int = KeyEvent.VK_Q,
        modifiers: Int = InputEvent.CTRL_DOWN_MASK,
        scope: HotkeyScope = HotkeyScope.GLOBAL,
        isEnabled: Boolean = true,
    ) = HotkeyBinding(
        action = action, keyCode = keyCode, modifiers = modifiers,
        isEnabled = isEnabled, scope = scope,
    )

    @Test
    fun `the shipped defaults have no conflicts`() {
        assertTrue(
            HotkeyConflictDetector.findConflicts(HotkeyBinding.DEFAULTS).isEmpty(),
            "defaults must ship conflict-free",
        )
    }

    @Test
    fun `duplicate global bindings are a conflict`() {
        val conflicts = HotkeyConflictDetector.findConflicts(
            listOf(
                binding(HotkeyAction.SHOW_QUICK_TRANSLATE),
                binding(HotkeyAction.LISTEN_TO_TEXT),
            )
        )
        assertEquals(1, conflicts.size)
        assertEquals(HotkeyConflictKind.DUPLICATE_GLOBAL, conflicts[0].kind)
        assertEquals(
            setOf(HotkeyAction.SHOW_QUICK_TRANSLATE, HotkeyAction.LISTEN_TO_TEXT),
            conflicts[0].actions,
        )
    }

    @Test
    fun `duplicate local bindings are a conflict`() {
        val conflicts = HotkeyConflictDetector.findConflicts(
            listOf(
                binding(HotkeyAction.FOCUS_INPUT, scope = HotkeyScope.LOCAL),
                binding(HotkeyAction.FOCUS_OUTPUT, scope = HotkeyScope.LOCAL),
            )
        )
        assertEquals(1, conflicts.size)
        assertEquals(HotkeyConflictKind.DUPLICATE_LOCAL, conflicts[0].kind)
    }

    @Test
    fun `the same combination bound globally and locally collides`() {
        val conflicts = HotkeyConflictDetector.findConflicts(
            listOf(
                binding(HotkeyAction.SHOW_QUICK_TRANSLATE, scope = HotkeyScope.GLOBAL),
                binding(HotkeyAction.TRANSLATE, scope = HotkeyScope.LOCAL),
            )
        )
        assertEquals(1, conflicts.size)
        assertEquals(HotkeyConflictKind.LOCAL_GLOBAL_COLLISION, conflicts[0].kind)
        assertEquals(
            setOf(HotkeyAction.SHOW_QUICK_TRANSLATE, HotkeyAction.TRANSLATE),
            conflicts[0].actions,
        )
    }

    @Test
    fun `duplicate capture bindings are a conflict, but capture does not collide with main scopes`() {
        val captureDup = HotkeyConflictDetector.findConflicts(
            listOf(
                binding(HotkeyAction.OCR_ACTION_TRANSLATE, scope = HotkeyScope.CAPTURE),
                binding(HotkeyAction.OCR_ACTION_COPY_TEXT, scope = HotkeyScope.CAPTURE),
            )
        )
        assertEquals(1, captureDup.size)
        assertEquals(HotkeyConflictKind.DUPLICATE_CAPTURE, captureDup[0].kind)

        val captureVsLocal = HotkeyConflictDetector.findConflicts(
            listOf(
                binding(HotkeyAction.OCR_ACTION_TRANSLATE, scope = HotkeyScope.CAPTURE),
                binding(HotkeyAction.TRANSLATE, scope = HotkeyScope.LOCAL),
            )
        )
        assertTrue(captureVsLocal.isEmpty(), "capture keys live on the overlay, not the window")
    }

    @Test
    fun `disabled and unbound bindings never conflict`() {
        val conflicts = HotkeyConflictDetector.findConflicts(
            listOf(
                binding(HotkeyAction.SHOW_QUICK_TRANSLATE, isEnabled = false),
                binding(HotkeyAction.LISTEN_TO_TEXT),
                binding(HotkeyAction.OPEN_OCR, keyCode = 0, modifiers = 0),
                binding(HotkeyAction.SHOW_DICTIONARY, keyCode = 0, modifiers = 0),
            )
        )
        assertTrue(conflicts.isEmpty(), "only enabled, bound bindings contend: $conflicts")
    }

    @Test
    fun `conflictsFor reports only the conflicts involving the action`() {
        val bindings = listOf(
            binding(HotkeyAction.SHOW_QUICK_TRANSLATE),
            binding(HotkeyAction.LISTEN_TO_TEXT),
            binding(HotkeyAction.OPEN_OCR, keyCode = KeyEvent.VK_Z),
        )
        assertEquals(1, HotkeyConflictDetector.conflictsFor(HotkeyAction.SHOW_QUICK_TRANSLATE, bindings).size)
        assertTrue(HotkeyConflictDetector.conflictsFor(HotkeyAction.OPEN_OCR, bindings).isEmpty())
    }

    @Test
    fun `a combination used three times is one conflict naming all three actions`() {
        val conflicts = HotkeyConflictDetector.findConflicts(
            listOf(
                binding(HotkeyAction.SHOW_QUICK_TRANSLATE),
                binding(HotkeyAction.LISTEN_TO_TEXT),
                binding(HotkeyAction.OPEN_OCR),
            )
        )
        assertEquals(1, conflicts.size)
        assertEquals(3, conflicts[0].actions.size)
    }
}
