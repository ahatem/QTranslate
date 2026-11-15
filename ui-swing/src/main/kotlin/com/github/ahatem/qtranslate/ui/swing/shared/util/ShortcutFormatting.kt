package com.github.ahatem.qtranslate.ui.swing.shared.util

import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * The one way a key combination is written for people.
 *
 * Every surface that shows a shortcut — menu accelerators, pane tooltips, the settings
 * table, the recorder preview — reads it from here, so `Ctrl+Shift+X` never becomes
 * `Ctrl + Shift + X` in one place and `Ctrl+Shift+X` in another.
 *
 * Takes the raw key code and modifier mask because the callers hold different things: a
 * [com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding], a
 * [com.github.ahatem.qtranslate.core.settings.data.HotkeyConflict], and a Swing
 * [javax.swing.KeyStroke] all reduce to these two integers.
 *
 * A [keyCode] of 0 is the project's "no main key" sentinel, so it renders as the modifiers
 * alone — which is what a recorder preview shows while the user is still holding them.
 *
 * Modifier order is fixed (Ctrl, Alt, Shift, Meta) rather than following the order keys
 * were pressed, so the same combination always reads the same way.
 */
internal fun formatChord(keyCode: Int, modifiers: Int): String = buildList {
    if (modifiers and InputEvent.CTRL_DOWN_MASK != 0) add("Ctrl")
    if (modifiers and InputEvent.ALT_DOWN_MASK != 0) add("Alt")
    if (modifiers and InputEvent.SHIFT_DOWN_MASK != 0) add("Shift")
    if (modifiers and InputEvent.META_DOWN_MASK != 0) add("Meta")
    if (keyCode != 0) add(KeyEvent.getKeyText(keyCode))
}.joinToString("+")
