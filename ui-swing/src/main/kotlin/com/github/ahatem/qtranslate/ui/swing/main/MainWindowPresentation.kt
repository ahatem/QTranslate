package com.github.ahatem.qtranslate.ui.swing.main

/**
 * How the main window is first presented and later brought back for interaction.
 *
 * A login launch builds the frame as usual but never shows it, so no window flashes and no
 * taskbar entry appears. Every path that intentionally presents the window (global show hotkey,
 * tray restore, second-instance activation) goes through [showAndFocus], which makes it visible,
 * clears a minimized state, raises it and lands keyboard focus in the input editor.
 *
 * The window operations are passed in so the ordering and the startup decision can be tested
 * without a display.
 */
internal class MainWindowPresentation(
    private val setVisible: (Boolean) -> Unit,
    private val restoreNormalState: () -> Unit,
    private val toFront: () -> Unit,
    private val focusInput: () -> Unit,
) {

    /** First presentation at the end of construction. A hidden start never shows the window. */
    fun presentAtStartup(initiallyHidden: Boolean) {
        setVisible(!initiallyHidden)
    }

    fun showAndFocus() {
        setVisible(true)
        restoreNormalState()
        toFront()
        focusInput()
    }
}
