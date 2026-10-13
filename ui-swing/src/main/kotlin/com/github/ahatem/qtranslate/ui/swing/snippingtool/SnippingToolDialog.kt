package com.github.ahatem.qtranslate.ui.swing.snippingtool

import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
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
    private val settingsStore: SettingsStore,
    private val localizer: LocalizationManager? = null
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

        val hotkeys = settingsStore.state.value.workingConfiguration.hotkeys

        fun shortcutFor(action: HotkeyAction): String? =
            hotkeys.find { it.action == action && it.isEnabled }
                ?.toDisplayString()
                ?.takeIf { it.isNotEmpty() }

        val buttonLabels = ScreenCapturePanel.ButtonLabels(
            translate = localizer?.getString("settings_hotkeys.action_ocr_translate")?.takeIf { !it.startsWith("settings_hotkeys.") } ?: "Translate",
            copyText  = localizer?.getString("settings_hotkeys.action_ocr_copy_text")?.takeIf { !it.startsWith("settings_hotkeys.") } ?: "Copy Text",
            copyImage = localizer?.getString("settings_hotkeys.action_ocr_copy_image")?.takeIf { !it.startsWith("settings_hotkeys.") } ?: "Copy Image",
            saveImage = localizer?.getString("settings_hotkeys.action_ocr_save_image")?.takeIf { !it.startsWith("settings_hotkeys.") } ?: "Save Image",
            recrop    = localizer?.getString("settings_hotkeys.action_ocr_recrop")?.takeIf { !it.startsWith("settings_hotkeys.") } ?: "Re-crop",
            cancel    = localizer?.getString("settings_hotkeys.action_ocr_cancel")?.takeIf { !it.startsWith("settings_hotkeys.") } ?: "Cancel"
        )

        val buttonTooltips = ScreenCapturePanel.ButtonTooltips(
            translate = shortcutFor(HotkeyAction.OCR_TRANSLATE),
            copyText  = shortcutFor(HotkeyAction.OCR_COPY_TEXT),
            copyImage = shortcutFor(HotkeyAction.OCR_COPY_IMAGE),
            saveImage = shortcutFor(HotkeyAction.OCR_SAVE_IMAGE),
            recrop    = shortcutFor(HotkeyAction.OCR_RECROP),
            cancel    = shortcutFor(HotkeyAction.OCR_CANCEL)
        )

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
            },
            buttonLabels = buttonLabels,
            buttonTooltips = buttonTooltips
        )

        panel.attachController(controller)
        panel.setBounds(0, 0, bounds.width, bounds.height)
        contentPane.add(panel, BorderLayout.CENTER)

        setupListeners()
        panel.render(initialState)

        isVisible = true
    }

    private fun dispatchOcrAndTranslate(image: ImageData) {
        val dest = settingsStore.state.value.workingConfiguration.ocrTranslationDestination
        val openInQuickTranslate = dest == OcrTranslationDestination.POPUP
        if (!openInQuickTranslate) {
            owner.isVisible = true
            owner.state = Frame.NORMAL
            owner.toFront()
        }
        mainStore.dispatch(MainIntent.OcrAndTranslateImage(image, openInQuickTranslate = openInQuickTranslate))
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
        // Esc always closes the snipping tool as a fallback
        rootPane.registerKeyboardAction(
            { dispose() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        val hotkeys = settingsStore.state.value.workingConfiguration.hotkeys

        fun registerAction(action: HotkeyAction, block: () -> Unit) {
            val binding = hotkeys.find { it.action == action && it.isEnabled } ?: return
            val keyStroke = binding.toKeyStroke() ?: return
            rootPane.registerKeyboardAction(
                { block() },
                keyStroke,
                JComponent.WHEN_IN_FOCUSED_WINDOW
            )
        }

        registerAction(HotkeyAction.OCR_TRANSLATE) {
            panel.getSelectedImage()?.let {
                dispatchOcrAndTranslate(it.toImageData("png"))
            }
        }
        registerAction(HotkeyAction.OCR_COPY_TEXT) {
            panel.getSelectedImage()?.let {
                dispatchOcrAndCopyText(it.toImageData("png"))
            }
        }
        registerAction(HotkeyAction.OCR_COPY_IMAGE) {
            panel.getSelectedImage()?.let {
                copyImageToClipboard(it)
                dispose()
            }
        }
        registerAction(HotkeyAction.OCR_SAVE_IMAGE) {
            panel.getSelectedImage()?.let {
                saveImageToFile(it)
                dispose()
            }
        }
        registerAction(HotkeyAction.OCR_RECROP) {
            controller.resetToIdle()
        }
        registerAction(HotkeyAction.OCR_CANCEL) {
            dispose()
        }

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
