package com.github.ahatem.qtranslate.core.settings.data

import java.awt.event.InputEvent
import java.awt.event.KeyEvent

enum class HotkeyPresetKind {
    LEGACY,
    MODERN,
    CUSTOM
}

/** Preset definitions for the complete hotkey list. The list itself remains the source of truth. */
object HotkeyPresets {

    val LEGACY: List<HotkeyBinding> = HotkeyBinding.DEFAULTS.map { it.copy() }.toList()

    val MODERN: List<HotkeyBinding> = LEGACY.map { binding ->
        when (binding.action) {
            HotkeyAction.SHOW_QUICK_TRANSLATE -> binding.copy(
                keyCode = KeyEvent.VK_Q,
                modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            )
            HotkeyAction.LISTEN_TO_TEXT -> binding.copy(
                keyCode = KeyEvent.VK_L,
                modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            )
            HotkeyAction.OPEN_OCR -> binding.copy(
                keyCode = KeyEvent.VK_O,
                modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            )
            HotkeyAction.REPLACE_WITH_TRANSLATION -> binding.copy(
                keyCode = KeyEvent.VK_R,
                modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            )
            HotkeyAction.SHOW_DICTIONARY -> binding.copy(
                keyCode = KeyEvent.VK_D,
                modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            )
            HotkeyAction.SHOW_IMAGES -> binding.copy(
                keyCode = KeyEvent.VK_I,
                modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
            )
            else -> binding.copy()
        }
    }.toList()

    fun identify(bindings: List<HotkeyBinding>): HotkeyPresetKind = when {
        sameBindings(bindings, LEGACY) -> HotkeyPresetKind.LEGACY
        sameBindings(bindings, MODERN) -> HotkeyPresetKind.MODERN
        else -> HotkeyPresetKind.CUSTOM
    }

    private fun sameBindings(left: List<HotkeyBinding>, right: List<HotkeyBinding>): Boolean =
        left.size == right.size &&
            left.sortedBy { it.action.ordinal } == right.sortedBy { it.action.ordinal }
}
