package com.github.ahatem.qtranslate.ui.swing.snippingtool

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.getVirtualScreenBounds
import com.github.ahatem.qtranslate.ui.swing.shared.util.toImageData
import java.awt.BorderLayout
import java.awt.Frame
import java.awt.Robot
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.KeyStroke

class SnippingToolDialog(
    owner: Frame,
    private val mainStore: MainStore
) : JDialog(owner, "", true) {

    private val panel: ScreenCapturePanel
    private val controller: ScreenCaptureController

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isUndecorated = true
        bounds = getVirtualScreenBounds()

        val screenshot = Robot().createScreenCapture(bounds)
        val initialState = ScreenCaptureState(screenshot = screenshot)

        controller = ScreenCaptureController(initialState) { newState ->
            val buttonsPosition = if (newState.selection != null) {
                panel.calculateButtonsPosition(newState.selection)
            } else null
            val stateWithButtons = newState.copy(buttonsPosition = buttonsPosition)
            panel.render(stateWithButtons)
            controller.updateState(stateWithButtons)
        }

        panel = ScreenCapturePanel(
            onCapture = { capturedImage ->
                val imageData = capturedImage.toImageData("png")
                dispatchOcrAndTranslate(imageData)
            },
            onCancel = {
                dispose()
            }
        )

        panel.attachController(controller)
        panel.setBounds(0, 0, bounds.width, bounds.height)
        contentPane.add(panel, BorderLayout.CENTER)

        setupListeners()
        panel.render(initialState)

        isVisible = true
    }

    private fun dispatchOcrAndTranslate(image: ImageData) {
        mainStore.dispatch(MainIntent.OcrAndTranslateImage(image))
        (owner as? JFrame)?.let {
            it.isVisible = true
            it.state = JFrame.NORMAL
            it.toFront()
        }
        dispose()
    }

    private fun setupListeners() {
        rootPane.registerKeyboardAction(
            { dispose() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        panel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val state = panel.currentStatePublic ?: return
                val sel = state.selection
                if (state.mode == CaptureMode.SELECTED && (sel == null || !sel.contains(e.point))) {
                    dispose()
                }
            }
        })
    }

}