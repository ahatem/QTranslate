package com.github.ahatem.qtranslate.ui.swing.snippingtool

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import com.github.ahatem.qtranslate.core.ocr.OcrWorkflowCommand
import com.github.ahatem.qtranslate.core.ocr.ResolvedOcrWorkflow
import com.github.ahatem.qtranslate.core.ocr.resolveOcrWorkflow
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.OcrPostSelectionAction
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.connectedUsableScreenBounds
import com.github.ahatem.qtranslate.ui.swing.shared.util.getVirtualScreenBounds
import com.github.ahatem.qtranslate.ui.swing.shared.util.toImageData
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The screen-capture overlay.
 *
 * One dialog serves every OCR entry workflow. [command] decides what happens after the first
 * valid selection; the toolbar remains the same and every toolbar action shares the same core
 * workflow intent. Local shortcuts are installed only on this root pane and use the CAPTURE
 * scope, so they cannot collide with the main window or global registry.
 */
class SnippingToolDialog(
    private val owner: Frame,
    private val mainStore: MainStore,
    private val settingsStore: SettingsStore,
    private val localizer: LocalizationManager,
    command: OcrWorkflowCommand = OcrWorkflowCommand.DEFAULT,
    /**
     * Called when the dialog closes without starting a workspace workflow. Used by the Workspace
     * when it hid itself to capture again, so a cancelled capture does not leave it invisible.
     */
    private val onDismissedWithoutWorkspace: () -> Unit = {},
    /**
     * Optional direct image receiver used by the Workspace's own Capture button. It bypasses the
     * main OCR workflow so a recognition failure in the ordinary path cannot leave the workspace
     * hidden. Normal OCR entry points leave this null.
     */
    private val onCapturedImage: ((BufferedImage) -> Unit)? = null,
) : JDialog(owner, "", true) {

    private val definition = settingsStore.state.value.originalConfiguration
    private val resolved: ResolvedOcrWorkflow = resolveOcrWorkflow(command, definition.ocr)
    private val usableDisplays: List<Rectangle> = connectedUsableScreenBounds()
    private val panel: ScreenCapturePanel
    private val controller: ScreenCaptureController

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isUndecorated = true
        bounds = getVirtualScreenBounds()

        val screenshot = Robot().createScreenCapture(bounds)
        val initialState = ScreenCaptureState(screenshot = screenshot)

        controller = ScreenCaptureController(
            currentState = initialState,
            onStateChanged = { newState ->
                val buttonsPosition = if (newState.selection != null) {
                    panel.calculateButtonsPosition(
                        selection = newState.selection,
                        displays = usableDisplays,
                        virtualOrigin = bounds.location,
                    )
                } else null
                val stateWithButtons = newState.copy(buttonsPosition = buttonsPosition)
                panel.render(stateWithButtons)
                controller.updateState(stateWithButtons)
            },
            selectionBounds = { Dimension(bounds.width, bounds.height) },
            onSelectionCommitted = ::onSelectionCommitted,
        )

        val labels = ScreenCapturePanel.ButtonLabels(
            translate = localizer.getString("screen_capture.translate"),
            copyText = localizer.getString("screen_capture.copy_text"),
            copyImage = localizer.getString("screen_capture.copy_image"),
            saveImage = localizer.getString("screen_capture.save_image"),
            recrop = localizer.getString("screen_capture.recrop"),
            cancel = localizer.getString("screen_capture.cancel"),
            moreActions = localizer.getString("screen_capture.more_actions"),
        )
        val shortcuts = installableCaptureShortcuts(definition.effectiveHotkeys)
            .associate { (binding, _) -> binding.action to binding }
        val tooltips = ScreenCapturePanel.ButtonTooltips(
            translate = withShortcut(localizer.getString("screen_capture.translate_tooltip"), shortcuts[HotkeyAction.OCR_ACTION_TRANSLATE]),
            copyText = withShortcut(localizer.getString("screen_capture.copy_text_tooltip"), shortcuts[HotkeyAction.OCR_ACTION_COPY_TEXT]),
            copyImage = withShortcut(localizer.getString("screen_capture.copy_image_tooltip"), shortcuts[HotkeyAction.OCR_ACTION_COPY_IMAGE]),
            saveImage = withShortcut(localizer.getString("screen_capture.save_image_tooltip"), shortcuts[HotkeyAction.OCR_ACTION_SAVE_IMAGE]),
            recrop = withShortcut(localizer.getString("screen_capture.recrop_tooltip"), shortcuts[HotkeyAction.OCR_ACTION_RECROP]),
            cancel = withShortcut(localizer.getString("screen_capture.cancel_tooltip"), shortcuts[HotkeyAction.OCR_ACTION_CANCEL]),
        )

        panel = ScreenCapturePanel(
            onTranslate = { capturedImage -> performWorkflow(OcrPostSelectionAction.TRANSLATE, capturedImage) },
            onCopyText = { capturedImage -> performWorkflow(OcrPostSelectionAction.COPY_TEXT, capturedImage) },
            onCopyImage = { capturedImage -> performWorkflow(OcrPostSelectionAction.COPY_IMAGE, capturedImage) },
            onSaveImage = { capturedImage ->
                if (saveImageToFile(capturedImage)) dispose()
            },
            onRecrop = { controller.resetToIdle() },
            onCancel = { dispose() },
            buttonLabels = labels,
            buttonTooltips = tooltips,
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
        installCaptureShortcuts()
        panel.render(initialState)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                onDismissedWithoutWorkspace()
            }
        })

        isVisible = true
    }

    /**
     * Shift is the documented one-gesture override back to the toolbar. It is checked only for
     * the selection that starts in IDLE, so adjusting an existing selection still behaves as a
     * normal move/resize.
     */
    private fun onSelectionCommitted(shiftDown: Boolean) {
        val action = resolved.action
        if (action == OcrPostSelectionAction.SHOW_ACTIONS) return
        if (shiftDown) return // The controller has already made the toolbar visible.
        val image = panel.getSelectedImage() ?: return
        performWorkflow(action, image)
    }

    private fun performWorkflow(action: OcrPostSelectionAction, image: BufferedImage) {
        if (action == OcrPostSelectionAction.SHOW_ACTIONS) return
        if (action == OcrPostSelectionAction.OPEN_WORKSPACE && onCapturedImage != null) {
            onCapturedImage.invoke(image)
            dispose()
            return
        }
        val imageData = image.toImageData("png")
        mainStore.dispatch(
            MainIntent.OcrWorkflowImage(
                image = imageData,
                action = action,
                destinationOverride = resolved.destinationOverride,
            )
        )
        dispose()
    }

    private fun saveImageToFile(image: BufferedImage): Boolean {
        val chooser = JFileChooser().apply {
            dialogTitle = localizer.getString("screen_capture.save_image_title")
            fileFilter = FileNameExtensionFilter(
                localizer.getString("screen_capture.save_image_filter"),
                "png",
            )
            selectedFile = File("screenshot.png")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false
        var file = chooser.selectedFile
        if (!file.name.endsWith(".png", ignoreCase = true)) {
            file = File("${file.absolutePath}.png")
        }
        return runCatching { ImageIO.write(image, "PNG", file) }.getOrDefault(false)
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

    /**
     * Installs only CAPTURE-scope bindings, and only while this dialog is the focused window.
     * Enter and Escape stay reserved for the primary action and cancellation; binding either
     * would make the documented keyboard contract unpredictable.
     */
    private fun installCaptureShortcuts() {
        installableCaptureShortcuts(definition.effectiveHotkeys).forEach { (binding, toolbarAction) ->
            val stroke = binding.toKeyStroke() ?: return@forEach
            val key = "ocrCapture_${binding.action.name}"
            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, key)
            rootPane.actionMap.put(
                key,
                object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent?) = panel.trigger(toolbarAction)
                }
            )
        }
    }

    private fun withShortcut(base: String, binding: HotkeyBinding?): String {
        if (binding == null || !binding.hasBinding) return base
        val display = formatShortcut(binding)
        return if (display.isBlank()) base else "$base ($display)"
    }

    private fun formatShortcut(binding: HotkeyBinding): String = buildList {
        if (binding.modifiers and java.awt.event.InputEvent.CTRL_DOWN_MASK != 0) add("Ctrl")
        if (binding.modifiers and java.awt.event.InputEvent.ALT_DOWN_MASK != 0) add("Alt")
        if (binding.modifiers and java.awt.event.InputEvent.SHIFT_DOWN_MASK != 0) add("Shift")
        if (binding.modifiers and java.awt.event.InputEvent.META_DOWN_MASK != 0) add("Meta")
        add(KeyEvent.getKeyText(binding.keyCode))
    }.joinToString("+")

}
