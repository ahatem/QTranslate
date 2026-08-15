package com.github.ahatem.qtranslate.ui.swing.quciktranslate

import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.Dimension
import java.awt.Frame
import java.awt.MouseInfo
import javax.swing.*

data class LoadingIndicatorState(
    val isVisible: Boolean
) : UiState

/**
 * The small progress marker that follows the pointer while a popup translation is being fetched.
 *
 * Owned by Swing's shared hidden frame rather than by the main window. It exists precisely for the
 * case where the main window is hidden in the tray, and an owned window is suppressed by the
 * platform while its owner is hidden — so owning it by the main window meant it never appeared at
 * the only time it was wanted.
 */
class LoadingIndicator(owner: Frame) : JWindow(), Renderable<LoadingIndicatorState> {

    private val positionUpdater = Timer(10) {
        val mouseLocation = MouseInfo.getPointerInfo().location
        setLocation(mouseLocation.x, mouseLocation.y + 20)
    }

    init {
        isAlwaysOnTop = true
        focusableWindowState = false
        type = Type.UTILITY

        val progressBar = JProgressBar().apply {
            isIndeterminate = true
            preferredSize = Dimension(45, 10)
            border = BorderFactory.createMatteBorder(2, 2, 2, 2, UIManager.getColor("Button.borderColor").darker())
            putClientProperty("JProgressBar.square", true)
        }
        contentPane.add(progressBar)
        pack()
    }

    override fun render(state: LoadingIndicatorState) {
        val shouldBeVisible = state.isVisible

        if (isVisible != shouldBeVisible) {
            isVisible = shouldBeVisible
            if (shouldBeVisible) {
                positionUpdater.start()
            } else {
                positionUpdater.stop()
            }
        }
    }
}