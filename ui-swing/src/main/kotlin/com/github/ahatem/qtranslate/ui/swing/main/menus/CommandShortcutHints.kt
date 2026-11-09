package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JMenuItem
import javax.swing.KeyStroke

/**
 * The shortcut chords shown beside menu items, derived from the user's configured
 * hotkey bindings.
 *
 * ### Integration seam
 *
 * CommandSpec (Workstream B) will become the single command-metadata source for
 * menus. Until it lands, this class is the one place that turns bindings into
 * display hints: menus consume [chordFor]/[keyStrokeFor] and never hardcode a
 * chord, and no new defaults are introduced. Re-pointing at CommandSpec means
 * rebuilding these hints from the command metadata (same action keys) and
 * deleting [fromBindings]/[formatChord] — the menu call sites do not change.
 *
 * Chords are display-only: hotkey delivery stays with the QInput backend
 * (GLOBAL) and the root-pane InputMap (LOCAL), exactly as configured. A hint is
 * present only for an enabled binding that has a key, so an unbound or disabled
 * shortcut shows nothing.
 */
class CommandShortcutHints private constructor(
    private val bindings: Map<HotkeyAction, HotkeyBinding>,
) {
    /** Display chord for [action], or null when it has no enabled binding. */
    fun chordFor(action: HotkeyAction): String? =
        bindings[action]?.takeIf { it.isEnabled && it.hasBinding }?.let(::formatChord)

    /** Swing keystroke for [action], or null when it has no enabled binding. */
    fun keyStrokeFor(action: HotkeyAction): KeyStroke? =
        bindings[action]?.takeIf { it.isEnabled && it.hasBinding }?.toKeyStroke()

    companion object {
        /** No hints: the default for menus built without binding context. */
        val EMPTY = CommandShortcutHints(emptyMap())

        /** Builds hints from the effective configured bindings. */
        fun fromBindings(bindings: List<HotkeyBinding>): CommandShortcutHints =
            CommandShortcutHints(bindings.associateBy { it.action })

        internal fun formatChord(binding: HotkeyBinding): String = buildList {
            if (binding.modifiers and InputEvent.CTRL_DOWN_MASK != 0) add("Ctrl")
            if (binding.modifiers and InputEvent.ALT_DOWN_MASK != 0) add("Alt")
            if (binding.modifiers and InputEvent.SHIFT_DOWN_MASK != 0) add("Shift")
            if (binding.modifiers and InputEvent.META_DOWN_MASK != 0) add("Meta")
            add(KeyEvent.getKeyText(binding.keyCode))
        }.joinToString("+")
    }
}

/**
 * Shows [action]'s configured chord as the menu item's accelerator (right-aligned
 * in the popup) and appends it to the tooltip. A no-op when the action has no
 * enabled binding, so unbound shortcuts stay undiscoverable by accident rather
 * than advertising a dead key.
 */
internal fun JMenuItem.applyShortcutHint(hints: CommandShortcutHints, action: HotkeyAction) {
    val chord = hints.chordFor(action) ?: return
    accelerator = hints.keyStrokeFor(action)
    toolTipText = "$text ($chord)"
}
