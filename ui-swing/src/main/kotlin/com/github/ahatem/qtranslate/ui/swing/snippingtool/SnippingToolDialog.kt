package com.github.ahatem.qtranslate.ui.swing.snippingtool

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import com.github.ahatem.qtranslate.core.settings.data.OcrTranslationDestination
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.getVirtualScreenBounds
import com.github.ahatem.qtranslate.ui.swing.shared.util.toImageData
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

class SnippingToolDialog(
    private val owner: Frame,
    private val mainStore: MainStore,
    private val settingsStore: SettingsStore
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
            onTranslate = { capturedImage ->
                val imageData = capturedImage.toImageData("png")
                dispatchOcrAndTranslate(imageData)
            },
            onCopyText = { capturedImage ->
                val imageData = capturedImage.toImageData("png")
                dispatchOcrAndCopyText(imageData)
            },
            onCopyImage = { capturedImage ->
                copyImageToClipboard(capturedImage)
                dispose()
            },
            onSaveImage = { capturedImage ->
                saveImageToFile(capturedImage)
                dispose()
            },
            onRecrop = {
                controller.resetToIdle()
            },
            onCancel = {
                dispose()
            }
        )

        panel.attachController(controller)
        panel.setBounds(0, 0, bounds.width, bounds.height)
        contentPane.add(panel, BorderLayout.CENTER)

        // The Translate button is presented as the primary action, so Enter
        // activates it (#364). Swing's default-button mechanism keeps Space on a
        // focused secondary button and the existing Escape binding untouched, and
        // only fires while the button is enabled (a selection exists).
        rootPane.defaultButton = panel.translateButton

        setupListeners()
        panel.render(initialState)

        isVisible = true
    }

    private fun dispatchOcrAndTranslate(image: ImageData) {
        val destination = settingsStore.state.value.workingConfiguration.ocrTranslationDestination
        if (shouldPresentMainWindow(destination)) {
            owner.isVisible = true
            owner.state = Frame.NORMAL
            owner.toFront()
        }
        mainStore.dispatch(MainIntent.OcrAndTranslateImage(image, destination))
        dispose()
    }

    private fun dispatchOcrAndCopyText(image: ImageData) {
        mainStore.dispatch(MainIntent.OcrAndCopyText(image))
        dispose()
    }

    private fun copyImageToClipboard(image: BufferedImage) {
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            @Throws(UnsupportedFlavorException::class)
            override fun getTransferData(flavor: DataFlavor): Any {
                if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
                return image
            }
        }
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
        }
    }

    private fun saveImageToFile(image: BufferedImage) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Save Captured Image"
            fileFilter = FileNameExtensionFilter("PNG Image (*.png)", "png")
            selectedFile = File("screenshot.png")
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            var file = chooser.selectedFile
            if (!file.name.endsWith(".png", ignoreCase = true)) {
                file = File("${file.absolutePath}.png")
            }
            runCatching { ImageIO.write(image, "PNG", file) }
        }
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

/**
 * The capture dialog minimises the main window before it opens. Only the main-window
 * destination may bring it back: the popup destination must leave it hidden so the result
 * appears in Quick Translate alone.
 */
internal fun shouldPresentMainWindow(destination: OcrTranslationDestination): Boolean =
    destination == OcrTranslationDestination.MAIN_WINDOW
