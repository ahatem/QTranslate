package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import javax.swing.JMenuItem
import javax.swing.KeyStroke

/**
 * The shortcut chords shown beside menu items, derived from the user's configured
 * hotkey bindings.
 *
 * Presentation-only: command identity, names, categories, surfaces, default
 * bindings, and eligibility live in [CommandRegistry][com.github.ahatem.qtranslate.core.settings.data.CommandRegistry]
 * (one [com.github.ahatem.qtranslate.core.settings.data.CommandSpec] per
 * [HotkeyAction]) and are never duplicated here. This class takes the effective
 * user bindings for those actions and turns them into display chords: menus
 * consume [chordFor]/[keyStrokeFor] and never hardcode a chord, and no new
 * defaults are introduced.
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

        /** The chord for one of these bindings, in the shared notation. */
        internal fun formatChord(binding: HotkeyBinding): String =
            com.github.ahatem.qtranslate.ui.swing.shared.util.formatChord(binding.keyCode, binding.modifiers)
    }
}

/**
 * [this] label with [action]'s configured chord appended, or unchanged when the action has no
 * enabled binding.
 *
 * For tooltips on controls that trigger a command: the control is found by its icon, so the
 * chord is the only place the keyboard route to the same action is written down. An unbound
 * action stays silent rather than advertising a key that does nothing.
 */
internal fun String.withShortcutChord(hints: CommandShortcutHints, action: HotkeyAction): String =
    hints.chordFor(action)?.let { "$this ($it)" } ?: this

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
