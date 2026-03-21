package com.github.ahatem.qtranslate.ui.swing.quciktranslate

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorPopupButton
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorSelectorState
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.*
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ComponentMover
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ComponentResizer
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.*
import java.awt.event.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.math.abs
import kotlin.math.max


class QuickTranslateDialog(
    private val owner: Frame,
    private val iconManager: IconManager,
    private val onDismiss: () -> Unit,
    onTranslatorSelected: (String) -> Unit,
    private val onListen: () -> Unit,
    private val onCopy: () -> Unit,
    private val onSavePosition: (Position) -> Unit,
    private val onSaveSize: (Size) -> Unit,
    private val onPinToggled: () -> Unit
) : JDialog(owner, ModalityType.MODELESS), Renderable<QuickTranslateDialogState> {

    private companion object {
        const val MAX_WIDTH_SCALE = 0.40
        const val MAX_HEIGHT_SCALE = 0.30
        const val RESIZE_HANDLE_SIZE = 8
        const val PINNED_BORDER_WIDTH = 4
        const val COPY_FEEDBACK_DURATION_MS = 1000
        const val FADE_MS = 160
        const val FADE_STEPS = 8
        const val IDLE_HIDE_MS_DEFAULT = 3000
        const val RESIZE_SAVE_DEBOUNCE_MS = 180
    }

    // theme colors cached
    private val borderColor = UIManager.getColor("Component.borderColor")
    private val accentBorderColor = UIManager.getColor("Component.focusedBorderColor")
        ?: UIManager.getColor("Component.accentColor")
        ?: borderColor
    private val toolbarSelectedBg = UIManager.getColor("Button.toolbar.selectedBackground")
    private val toolbarSelectedFg = UIManager.getColor("Button.toolbar.selectedForeground")
    private val labelFg = UIManager.getColor("Label.foreground")

    // title + controls
    private val languagePairLabel = JLabel().apply { putClientProperty("FlatLaf.styleClass", "h4") }
    private val translatorComboBox = TranslatorPopupButton(iconManager, onTranslatorSelected)

    private val pinButton = createButtonWithIcon(iconManager, "icons/lucide/pin.svg", 14)
    private val listenButton = createButtonWithIcon(iconManager, "icons/lucide/volume.svg", 14)
    private val copyButton = createButtonWithIcon(iconManager, "icons/lucide/copy-text.svg", 14)
    private val closeButton = createButtonWithIcon(iconManager, "icons/lucide/close.svg", 16)

    // content
    private val outputTextArea = AdvancedTextPane(
        onTextChanged = {},
        onTranslateRequest = {},
        onListenRequest = { onListen() }
    ).apply {
        isEditable = false
        border = EmptyBorder(6, 6, 6, 6)
    }

    private val topPanel = createTopPanel()

    // sizing/measuring
    private val measurePane: JTextPane by lazy {
        JTextPane().apply {
            editorKit = outputTextArea.editorKit
            isEditable = false
            putClientProperty("JEditorPane.honorDisplayProperties", true)
        }
    }

    // timers and state
    private val fadeLock = AtomicBoolean(false)
    private var fadeTimer: Timer? = null
    private var copyFeedbackTimer: Timer? = null
    private var resizeSaveTimer: Timer? = null

    // idle/auto-hide manager (single timer)
    private var idleHideTimer: Timer? = null
    private var idleHideDelayMs = IDLE_HIDE_MS_DEFAULT

    // flags
    private var isDragging = false
    private var isResizing = false
    private var isPinned = false
    private var wasManuallyMoved = false
    private var currentConfig: DialogConfig? = null

    private var lastRenderedText: String? = null

    // mouse presence detection via AWT
    private var awtMouseListener: AWTEventListener? = null
    private var isMouseOver = false

    init {
        isUndecorated = true
        isAlwaysOnTop = true
        minimumSize = Dimension(350, 120)
        focusableWindowState = false

        val wrapperPanel = JPanel(BorderLayout()).apply {
            val borderSize = RESIZE_HANDLE_SIZE / 2
            border = EmptyBorder(borderSize, borderSize, borderSize, borderSize)
            isOpaque = false
        }
        contentPane = wrapperPanel

        val mainPanel = JPanel(BorderLayout())
        wrapperPanel.add(mainPanel, BorderLayout.CENTER)

        val textScrollPane = JScrollPane(outputTextArea).apply {
            putClientProperty(
                FlatClientProperties.STYLE,
                "borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; innerOutlineWidth: 0;"
            )
            border = null
            viewport.border = null

            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(textScrollPane, BorderLayout.CENTER)

        setupWindowBehavior(topPanel)

        idleHideTimer = Timer(idleHideDelayMs) {
            if (!isPinned) hideDialog()
            (it.source as Timer).stop()
        }.apply { isRepeats = false }
    }

    // Render entrypoint
    override fun render(state: QuickTranslateDialogState) {
        val wasVisible = isVisible
        val visibilityChanged = wasVisible != state.isVisible

        if (visibilityChanged) {
            if (state.isVisible) {
                currentConfig = state.config
                wasManuallyMoved = false

                // ensure correct font before rendering or measuring text
                outputTextArea.updateFontsAndRescanDocument(
                    newPrimary = state.config.font.toFont(),
                    newFallback = state.config.fallbackFont.toFont()
                )

                updateContent(state)
                applySize(state.translatedText)
                applyPosition()
                showDialog()
            } else {
                hideDialog()
            }
            return
        }

        if (!isVisible) return

        val pinStateChanged = this.isPinned != state.isPinned

        updateContent(state)

        if (pinStateChanged) handlePinState(state)

        // only refresh font when user changed it
        if (!isResizing && !isDragging) {
            outputTextArea.updateFontsAndRescanDocument(
                newPrimary = state.config.font.toFont(),
                newFallback = state.config.fallbackFont.toFont()
            )
        }
    }

    // Full content sync
    private fun updateContent(state: QuickTranslateDialogState) {
        this.isPinned = state.isPinned

        val source = state.sourceLanguage.tag.uppercase()
        val target = state.targetLanguage.tag.uppercase()
        languagePairLabel.text = "$source → $target"

        translatorComboBox.render(
            TranslatorSelectorState(
                availableTranslators = state.translatorSelectorState.availableTranslators,
                selectedTranslatorId = state.translatorSelectorState.selectedTranslatorId,
                isLoading = state.isLoading
            )
        )

        pinButton.toolTipText = if (state.isPinned) "Pinned — window will stay visible" else "Pin window"
        listenButton.toolTipText = state.strings.listenTooltip
        copyButton.toolTipText = state.strings.copyTooltip

        listenButton.isEnabled = state.actionsState.canListen && !state.isLoading
        copyButton.isEnabled = state.actionsState.canCopy && !state.isLoading

        val textToRender = if (state.isLoading) state.strings.loadingText else state.translatedText
        if (lastRenderedText != textToRender) {
            lastRenderedText = textToRender
            outputTextArea.render(textToRender, emptyList(), false)
        }
    }

    private fun handlePinState(state: QuickTranslateDialogState) {
        this.isPinned = state.isPinned
        updatePinButtonStyle(state.isPinned)

        if (state.isPinned) {
            stopIdleHide()
            fadeTo(1f, FADE_MS)
        } else {
            applyTransparency()
            startIdleHide()
        }
    }

    private fun updatePinButtonStyle(pinned: Boolean) {
        pinButton.putClientProperty("JButton.buttonType", "toolBarButton")
        pinButton.putClientProperty("JButton.selected", pinned)

        if (pinned) {
            pinButton.isContentAreaFilled = true
            pinButton.background = toolbarSelectedBg
            pinButton.foreground = toolbarSelectedFg ?: labelFg
            rootPane.border = BorderFactory.createLineBorder(accentBorderColor, PINNED_BORDER_WIDTH)
        } else {
            pinButton.isContentAreaFilled = false
            pinButton.background = null
            pinButton.foreground = labelFg
            val coloredBorderWidth = 2
            val emptyBorderWidth = PINNED_BORDER_WIDTH - coloredBorderWidth
            rootPane.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, coloredBorderWidth),
                BorderFactory.createEmptyBorder(
                    emptyBorderWidth,
                    emptyBorderWidth,
                    emptyBorderWidth,
                    emptyBorderWidth
                )
            )
        }
        pinButton.repaint()
    }

    private fun applyTransparency() {
        val transparency = currentConfig?.transparencyPercentage ?: 0
        val target = (100f - transparency) / 100f
        fadeTo(target, FADE_MS)
    }

    private fun fadeTo(targetOpacity: Float, durationMs: Int) {
        if (fadeLock.get()) return
        fadeLock.set(true)
        fadeTimer?.stop()

        val start = opacity
        val steps = max(1, FADE_STEPS)
        val stepDelay = max(10, durationMs / steps)
        var step = 0

        fadeTimer = Timer(stepDelay) {
            step++
            val t = step.toFloat() / steps
            val value = start + (targetOpacity - start) * t
            setOpacityIfDifferent(value)

            if (step >= steps) {
                (it.source as Timer).stop()
                fadeLock.set(false)
            }
        }.apply {
            isRepeats = true
            start()
        }
    }

    private fun setOpacityIfDifferent(value: Float) {
        if (abs(opacity - value) > 0.01f) opacity = value
    }

    private fun showDialog() {
        // apply initial opacity from config (without animation)
        val transparency = currentConfig?.transparencyPercentage ?: 0
        opacity = (100f - transparency) / 100f

        isVisible = true
        focusableWindowState = true
        installAwtMouseListener()
        if (!isPinned) startIdleHide()
    }

    private fun hideDialog() {
        if (!isVisible) return
        stopIdleHide()
        uninstallAwtMouseListener()
        isVisible = false
        focusableWindowState = false
        onSavePosition(location.toPosition())
        onSaveSize(size.toSize())
    }

    private fun startIdleHide() {
        // restart single idle timer
        idleHideTimer?.stop()
        idleHideTimer = Timer(idleHideDelayMs) { event ->
            if (!isPinned) fadeTo(0f, FADE_MS) // fade out visually
            // after fade complete, actually hide
            Timer(FADE_MS + 20) {
                if (!isPinned) {
                    hideDialog()
                }
                (it.source as Timer).stop()
            }.apply { isRepeats = false; start() }
            (event.source as Timer).stop()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun stopIdleHide() {
        idleHideTimer?.stop()
    }

    private fun installAwtMouseListener() {
        if (awtMouseListener != null) return
        awtMouseListener = AWTEventListener { ev ->
            val me = ev as? MouseEvent ?: return@AWTEventListener
            if (me.id != MouseEvent.MOUSE_MOVED && me.id != MouseEvent.MOUSE_ENTERED && me.id != MouseEvent.MOUSE_EXITED) return@AWTEventListener
            SwingUtilities.invokeLater {
                val p = MouseInfo.getPointerInfo()?.location ?: return@invokeLater
                val cp = Point(p)
                SwingUtilities.convertPointFromScreen(cp, contentPane)
                val over = contentPane.contains(cp)
                if (over != isMouseOver) {
                    isMouseOver = over
                    if (isMouseOver) {
                        stopIdleHide()
                        fadeTo(1f, FADE_MS)
                    } else {
                        if (!isPinned) {
                            applyTransparency()
                            startIdleHide()
                        }
                    }
                } else {
                    // mouse moved inside window: reset idle timer
                    if (isMouseOver && !isPinned) startIdleHide()
                }
            }
        }
        Toolkit.getDefaultToolkit()
            .addAWTEventListener(awtMouseListener, AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK)
    }

    private fun uninstallAwtMouseListener() {
        awtMouseListener?.let {
            Toolkit.getDefaultToolkit().removeAWTEventListener(it)
            awtMouseListener = null
            isMouseOver = false
        }
    }

    // sizing (reuse measurePane; skip heavy ops during resize)
    private fun applySize(text: String) {
        val config = currentConfig ?: return
        if (!config.autoSizeEnabled) {
            size = config.lastKnownSize.toDimension()
            return
        }

        if (isResizing) {
            // defer measurement until resize end
            return
        }

        val screenBounds = graphicsConfiguration.bounds
        val maxWidth = (screenBounds.width * MAX_WIDTH_SCALE).toInt()
        val maxHeight = (screenBounds.height * MAX_HEIGHT_SCALE).toInt()

        measurePane.font = outputTextArea.font
        if (measurePane.text != text) measurePane.text = text
        measurePane.size = Dimension(maxWidth, Int.MAX_VALUE)

        val textWidth = measurePane.preferredSize.width + 40
        val textHeight = measurePane.preferredSize.height + 30

        val borderSize = RESIZE_HANDLE_SIZE * 2
        val finalWidth = (textWidth + borderSize)
            .coerceAtMost(maxWidth)
            .coerceAtLeast(minimumSize.width)

        val nonTextHeight = topPanel.preferredSize.height + 20
        val finalHeight = (textHeight + nonTextHeight + borderSize)
            .coerceAtMost(maxHeight)
            .coerceAtLeast(minimumSize.height)

        if (width != finalWidth || height != finalHeight) {
            size = Dimension(finalWidth, finalHeight)
            if (isVisible) revalidate()
        }
    }

    private fun applyPosition() {
        val config = currentConfig ?: return
        if (wasManuallyMoved) return

        if (config.autoPositionEnabled) {
            val mouseLocation = MouseInfo.getPointerInfo()?.location ?: run {
                setLocationRelativeTo(owner)
                return
            }

            val screenBounds = graphicsConfiguration.bounds
            val dialogWidth = width
            val dialogHeight = height
            val offsetX = 10
            val offsetY = 10

            var x = mouseLocation.x + offsetX
            var y = mouseLocation.y + offsetY

            if (x + dialogWidth > screenBounds.x + screenBounds.width) {
                x = mouseLocation.x - dialogWidth - offsetX
            }

            if (y + dialogHeight > screenBounds.y + screenBounds.height) {
                y = mouseLocation.y - dialogHeight - offsetY
            }

            x = x.coerceAtLeast(screenBounds.x)
            y = y.coerceAtLeast(screenBounds.y)

            setLocation(x, y)
        } else {
            location = config.lastKnownPosition.toPoint()
        }
    }

    // Copy feedback: small animation reuse
    private fun showCopyFeedback() {
        copyFeedbackTimer?.stop()

        val originalIcon = copyButton.icon
        val checkIcon = iconManager.getIcon("icons/lucide/check.svg", 13, 13)
        copyButton.icon = (checkIcon as FlatSVGIcon).applyForegroundColorFilter()
        copyButton.foreground = UIManager.getColor("Button.successForeground") ?: Color(34, 197, 94)

        copyFeedbackTimer = Timer(COPY_FEEDBACK_DURATION_MS) {
            copyButton.icon = originalIcon
            copyButton.foreground = null
            (it.source as Timer).stop()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun createTopPanel(): JPanel {
        pinButton.addActionListener { onPinToggled() }
        listenButton.addActionListener { onListen() }
        copyButton.addActionListener {
            onCopy()
            showCopyFeedback()
        }
        closeButton.addActionListener { onDismiss() }

        listOf(pinButton, listenButton, copyButton).forEach { b ->
            b.putClientProperty("JButton.buttonType", "toolBarButton")
        }

        closeButton.apply {
            isFocusable = false
            putClientProperty("JButton.buttonType", "toolBarButton")
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    background = UIManager.getColor("InternalFrame.closeHoverBackground")
                    foreground = UIManager.getColor("InternalFrame.closeHoverForeground")
                    isContentAreaFilled = true
                    isBorderPainted = false
                }

                override fun mouseExited(e: MouseEvent) {
                    isContentAreaFilled = false
                    foreground = null
                }

                override fun mousePressed(e: MouseEvent) {
                    background = UIManager.getColor("InternalFrame.closePressedBackground")
                    foreground = UIManager.getColor("InternalFrame.closePressedForeground")
                    isContentAreaFilled = true
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (contains(e.point)) {
                        background = UIManager.getColor("InternalFrame.closeHoverBackground")
                        foreground = UIManager.getColor("InternalFrame.closeHoverForeground")
                    }
                }
            })
        }

        val separator = JPanel().apply {
            border = BorderFactory.createMatteBorder(0, 0, 0, 1, borderColor)
            preferredSize = Dimension(1, 24)
            maximumSize = Dimension(1, Int.MAX_VALUE)
            isOpaque = false
        }

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(languagePairLabel)
            add(translatorComboBox)
        }

        val rightPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)

            add(pinButton)
            add(listenButton)
            add(copyButton)

            add(Box.createRigidArea(Dimension(6, 0)))
            add(separator)
            add(Box.createRigidArea(Dimension(6, 0)))

            add(closeButton)
        }

        val finalPanel = JPanel().apply {
            isOpaque = false
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor)
        }

        val grid = GridBag(finalPanel)

        val verticalPadding = 4

        grid.fill(GridBagConstraints.BOTH)

            .weightX(1.0)
            .anchor(GridBagConstraints.WEST)
            .insets(verticalPadding, 0, verticalPadding, 0)
            .add(leftPanel)

            .weightX(0.0)
            .anchor(GridBagConstraints.EAST)
            .insets(verticalPadding, 0, verticalPadding, 0)
            .add(rightPanel)

        return finalPanel
    }


    private fun setupWindowBehavior(topPanel: JPanel) {
        val dragInsets = Insets(RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE)

        ComponentMover.builder()
            .destinationComponent(this)
            .build()
            .register(topPanel)

        val resizer = ComponentResizer.builder()
            .dragInsets(dragInsets)
            .minimumSize(minimumSize)
            .onResizeStart {
                // freeze opacity and suspend idle timer
                isResizing = true
                stopIdleHide()
                fadeTo(1f, FADE_MS)
            }
            .onResizeEnd {
                // restore opacity and save size once
                isResizing = false

                resizeSaveTimer?.stop()
                resizeSaveTimer = Timer(RESIZE_SAVE_DEBOUNCE_MS) {
                    onSaveSize(size.toSize())
                    // rescan fonts/doc after resize
                    currentConfig?.let { cfg ->
                        outputTextArea.updateFontsAndRescanDocument(
                            newPrimary = cfg.font.toFont(),
                            newFallback = cfg.fallbackFont.toFont()
                        )
                    }
                    (it.source as Timer).stop()
                }.apply { isRepeats = false; start() }

                if (!isPinned) {
                    applyTransparency()
                    startIdleHide()
                }
            }
            .build()

        resizer.register(this)

        val dragListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                isDragging = true
                wasManuallyMoved = true // mark manual move immediately
                stopIdleHide()
                autoHideStopForDrag()
            }

            override fun mouseReleased(e: MouseEvent) {
                isDragging = false
                onSavePosition(location.toPosition())
                if (!isPinned) startIdleHide()
            }
        }

        topPanel.addMouseListener(dragListener)
        topPanel.addMouseMotionListener(dragListener)

        // Also track manual window drag if user drags from edges (to catch non-top-panel moves)
        addMouseListener(dragListener)
        addMouseMotionListener(dragListener)

        addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {
                // reset idle when gaining focus
                if (!isPinned) startIdleHide()
            }

            override fun windowLostFocus(e: WindowEvent?) {
                // don't hide immediately on focus loss; start idle hide instead
                if (!isPinned) startIdleHide()
            }
        })

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = onDismiss()
            override fun windowClosed(e: WindowEvent) {
                uninstallAwtMouseListener()
            }
        })

        rootPane.registerKeyboardAction(
            {
                if (!isPinned) onDismiss()
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )
    }

    private fun autoHideStopForDrag() {
        // used to prevent premature hiding while user drags
        stopIdleHide()
        fadeTo(1f, FADE_MS / 2)
    }
}