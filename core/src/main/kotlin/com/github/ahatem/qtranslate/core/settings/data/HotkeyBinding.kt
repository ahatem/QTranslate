package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.Serializable
import javax.swing.KeyStroke

/**
 * Stable identifiers for every bindable action.
 * Never rename these — they are persisted in the config file.
 */
@Serializable
enum class HotkeyAction {
    SHOW_MAIN_WINDOW,
    SHOW_QUICK_TRANSLATE,
    LISTEN_TO_TEXT,
    OPEN_OCR,
    REPLACE_WITH_TRANSLATION,  // Rob #2 / Davide — translate and replace selected text
    CYCLE_TARGET_LANGUAGE,     // Yan #3 — cycle through available target languages
    SHOW_DICTIONARY,           // open floating dictionary popup
    SHOW_IMAGES,               // open floating image popup (default: Ctrl+Shift+Q, GLOBAL)
    TRANSLATE,                 // trigger translation (default: Ctrl+Enter, LOCAL)
    FOCUS_INPUT,               // move keyboard focus to the input text pane (default: Alt+1, LOCAL)
    FOCUS_OUTPUT,              // move keyboard focus to the output text pane (default: Alt+2, LOCAL)
    FOCUS_EXTRA_OUTPUT,        // move keyboard focus to the extra-output pane (default: Alt+3, LOCAL)
    COPY_TRANSLATION,          // copy the translated text (default: Ctrl+Shift+C, LOCAL)
    CLEAR_INPUT,               // clear the input pane (default: Ctrl+Shift+X, LOCAL)
    SWAP_LANGUAGES,            // swap source and target languages (default: Ctrl+Shift+S, LOCAL)
    OPEN_SETTINGS,             // open the settings dialog (default: Ctrl+Comma, LOCAL)
    SHOW_HISTORY,              // open the translation history dialog (default: Ctrl+Shift+H, LOCAL)
    TRANSLATE_DOCUMENT         // open the document translation dialog (default: Ctrl+Shift+D, LOCAL)
}

/**
 * Whether a hotkey fires globally (system-wide via the global input backend) or
 * locally (only when QTranslate has focus, via Swing InputMap).
 *
 * Global hotkeys intercept keys from any application — use sparingly.
 * Local hotkeys only fire inside QTranslate — safe for common shortcuts.
 *
 * Dinar's request: allow per-action control so e.g. Ctrl+Tab isn't
 * stolen from the browser while still keeping Ctrl+Q global.
 */
@Serializable
enum class HotkeyScope {
    GLOBAL,  // Registered with the global input backend — fires system-wide
    LOCAL    // Registered via Swing InputMap — fires only inside QTranslate
}

/**
 * A user-configurable hotkey binding stored as raw [keyCode] + [modifiers] integers.
 *
 * ### Why integers, not a string?
 * [KeyStroke.getKeyStroke] (String) fails for many keys (slash, page up, numpad keys).
 * Storing keyCode + modifiers avoids all string parsing.
 * Reconstruct: `KeyStroke.getKeyStroke(keyCode, modifiers)`
 *
 * [keyCode] = 0 means "no binding" (SHOW_MAIN_WINDOW uses double-Ctrl via raw key events).
 *
 * [isDoubleCtrlEnabled] only applies to [HotkeyAction.SHOW_MAIN_WINDOW].
 * When false the double-tap Ctrl sequence is suppressed so other applications
 * that react to Ctrl-key events are not accidentally triggered.
 */
@Serializable
data class HotkeyBinding(
    val action: HotkeyAction,
    val keyCode: Int = 0,
    val modifiers: Int = 0,
    val isEnabled: Boolean = true,
    val scope: HotkeyScope = HotkeyScope.GLOBAL,
    val isDoubleCtrlEnabled: Boolean = true   // SHOW_MAIN_WINDOW only
) {
    val hasBinding: Boolean get() = keyCode != 0

    fun toKeyStroke(): KeyStroke? =
        if (hasBinding) KeyStroke.getKeyStroke(keyCode, modifiers) else null

    companion object {
        val DEFAULTS: List<HotkeyBinding> = listOf(
            // SHOW_MAIN_WINDOW: double-Ctrl via raw key events — no KeyStroke, always GLOBAL
            HotkeyBinding(HotkeyAction.SHOW_MAIN_WINDOW,         keyCode = 0,                                          modifiers = 0,                                         scope = HotkeyScope.GLOBAL, isDoubleCtrlEnabled = true),
            HotkeyBinding(HotkeyAction.SHOW_QUICK_TRANSLATE,     keyCode = java.awt.event.KeyEvent.VK_Q,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.LISTEN_TO_TEXT,           keyCode = java.awt.event.KeyEvent.VK_E,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.OPEN_OCR,                 keyCode = java.awt.event.KeyEvent.VK_I,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.REPLACE_WITH_TRANSLATION, keyCode = java.awt.event.KeyEvent.VK_T,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.CYCLE_TARGET_LANGUAGE,    keyCode = java.awt.event.KeyEvent.VK_L,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.SHOW_DICTIONARY,          keyCode = java.awt.event.KeyEvent.VK_D,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.GLOBAL),
            // Shift+the quick-translate key: this is the same gesture on the same selection,
            // asking for pictures instead of words. Ctrl+Shift+I would read as a variant of OCR.
            HotkeyBinding(HotkeyAction.SHOW_IMAGES,              keyCode = java.awt.event.KeyEvent.VK_Q,               modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.GLOBAL),
            HotkeyBinding(HotkeyAction.TRANSLATE,                keyCode = java.awt.event.KeyEvent.VK_ENTER,            modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.FOCUS_INPUT,              keyCode = java.awt.event.KeyEvent.VK_1,                modifiers = java.awt.event.InputEvent.ALT_DOWN_MASK,   scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.FOCUS_OUTPUT,             keyCode = java.awt.event.KeyEvent.VK_2,                modifiers = java.awt.event.InputEvent.ALT_DOWN_MASK,   scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.FOCUS_EXTRA_OUTPUT,       keyCode = java.awt.event.KeyEvent.VK_3,                modifiers = java.awt.event.InputEvent.ALT_DOWN_MASK,   scope = HotkeyScope.LOCAL),
            // All LOCAL — these act on the focused window, so they must not take the key
            // combination away from other applications the way a GLOBAL binding would.
            HotkeyBinding(HotkeyAction.COPY_TRANSLATION,         keyCode = java.awt.event.KeyEvent.VK_C,                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.CLEAR_INPUT,              keyCode = java.awt.event.KeyEvent.VK_X,                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.SWAP_LANGUAGES,           keyCode = java.awt.event.KeyEvent.VK_S,                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.OPEN_SETTINGS,            keyCode = java.awt.event.KeyEvent.VK_COMMA,            modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK,  scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.SHOW_HISTORY,             keyCode = java.awt.event.KeyEvent.VK_H,                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.LOCAL),
            HotkeyBinding(HotkeyAction.TRANSLATE_DOCUMENT,       keyCode = java.awt.event.KeyEvent.VK_D,                modifiers = java.awt.event.InputEvent.CTRL_DOWN_MASK or java.awt.event.InputEvent.SHIFT_DOWN_MASK, scope = HotkeyScope.LOCAL),
        )
    }
}
