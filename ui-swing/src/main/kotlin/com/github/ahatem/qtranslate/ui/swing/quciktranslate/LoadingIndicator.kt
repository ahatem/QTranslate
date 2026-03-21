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

class LoadingIndicator(owner: Frame) : JWindow(owner), Renderable<LoadingIndicatorState> {

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