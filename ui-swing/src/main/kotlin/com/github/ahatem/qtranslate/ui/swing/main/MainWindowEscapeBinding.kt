package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.KeyStroke

/**
 * Fixed Escape binding for the main window (#216, first half).
 *
 * Pressing Escape while the main window is focused hides it (`isVisible = false`).
 * It never disposes the frame, clears input, or exits the application — the next
 * normal show action (tray click, global hotkey) restores the same window intact.
 *
 * Precedence is deliberate and relies on normal Swing InputMap ordering:
 * - No text pane or ordinary child owns Escape, so a single press from input,
 *   output, extra-output, or any other ordinary child reaches this binding and
 *   hides the window.
 * - An in-flight translation is cancelled instead of hiding, preserving the
 *   previous `cancel-translation` behavior (first Esc cancels, next Esc hides).
 * - An open menu/lightweight popup owns Escape for its own dismissal
 *   ([isChildHandlingEscape]), so this binding stays out of its way.
 *
 * Installed on the root pane with `WHEN_IN_FOCUSED_WINDOW`, so it works from any
 * ordinary main-window child without a global/native hook or scattered KeyListeners.
 */
internal class MainWindowEscapeBinding(
    private val rootPane: JRootPane,
    private val isTranslationInFlight: () -> Boolean = { false },
    private val isChildHandlingEscape: () -> Boolean = { false },
    private val onCancelTranslation: () -> Unit = {},
    private val onHide: () -> Unit = {},
) {

    fun register() {
        val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        inputMap.put(ESCAPE_KEYSTROKE, ACTION_KEY)
        rootPane.actionMap.put(ACTION_KEY, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                handleEscape()
            }
        })
    }

    fun handleEscape() {
        if (isChildHandlingEscape()) return
        if (isTranslationInFlight()) {
            onCancelTranslation()
            return
        }
        onHide()
    }

    internal companion object {
        val ESCAPE_KEYSTROKE: KeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        const val ACTION_KEY = "hide-main-window-on-escape"
    }
}
