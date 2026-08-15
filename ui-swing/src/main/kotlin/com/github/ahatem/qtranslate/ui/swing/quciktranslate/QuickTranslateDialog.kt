package com.github.ahatem.qtranslate.ui.swing.quciktranslate

import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
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
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.FloatingPopupBehavior
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.InlineLoadingBar
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DefinitionStrip
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.*
import java.awt.event.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.math.abs
import kotlin.math.max


class QuickTranslateDialog(
    /**
     * The main window, used only to position against. It is deliberately NOT this dialog's
     * owner -- see FloatingPopupBehavior for why a tray application must not own these.
     */
    private val owner: Frame,
    private val iconManager: IconManager,
    private val onDismiss: () -> Unit,
    onTranslatorSelected: (String) -> Unit,
    private val onListen: () -> Unit,
    private val onCopy: () -> Unit,
    private val onSavePosition: (Position) -> Unit,
    private val onSaveSize: (Size) -> Unit,
    private val onPinToggled: () -> Unit
) : JDialog(null as Frame?, ModalityType.MODELESS), Renderable<QuickTranslateDialogState> {

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

    // Read on use, not cached. This dialog is created once and outlives any number of theme
    // switches; a captured colour would pin the borders and button styling to whichever theme
    // happened to be active at startup. See refreshTheme.
    private val borderColor: Color? get() = UIManager.getColor("Component.borderColor")
    private val accentBorderColor: Color?
        get() = UIManager.getColor("Component.focusedBorderColor")
            ?: UIManager.getColor("Component.accentColor")
            ?: borderColor
    private val toolbarSelectedBg: Color? get() = UIManager.getColor("Button.toolbar.selectedBackground")
    private val toolbarSelectedFg: Color? get() = UIManager.getColor("Button.toolbar.selectedForeground")
    private val labelFg: Color? get() = UIManager.getColor("Label.foreground")

    /** A field, not an inline lambda, so it can be detached when the window goes away. */
    private val themeListener = java.beans.PropertyChangeListener { event ->
        if (event.propertyName == "lookAndFeel") SwingUtilities.invokeLater { refreshTheme() }
    }

    /** Panels carrying a themed divider, kept so it can be redrawn in the new theme's colour. */
    private val dividedPanels = mutableListOf<Pair<JPanel, () -> javax.swing.border.Border>>()

    /**
     * Re-applies the colours this dialog painted itself with.
     *
     * Borders keep the colour they were given, and switching look and feel does not revisit them,
     * so without this the popup keeps the old theme's edges against the new background.
     */
    private fun refreshTheme() {
        dividedPanels.forEach { (panel, borderOf) -> panel.border = borderOf() }
        updatePinButtonStyle(isPinned)
        revalidate()
        repaint()
    }

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

    private val loadingBar = InlineLoadingBar()
    private val definitionStrip = DefinitionStrip()

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
    private var copyFeedbackTimer: Timer? = null
    private var resizeSaveTimer: Timer? = null

    /**
     * Window flags, fading, idle-hide, pointer presence — shared with the other two popups.
     *
     * Declared ahead of [isPinned], whose setter writes into it. Kotlin initialises properties in
     * declaration order, so the other way round leaves a window in which assigning isPinned would
     * dereference null.
     */
    private val popup = FloatingPopupBehavior(
        window = this,
        owner = owner,
        minimumSize = Dimension(PopupSizing.minWidth(), PopupSizing.minHeight()),
        pinnedBorderWidth = PINNED_BORDER_WIDTH,
        resizeHandle = RESIZE_HANDLE_SIZE
    ).apply {
        configureIdleHide(
            delayMs = { (currentConfig?.idleTimeoutSeconds ?: 3) * 1000 },
            fadeMs = FADE_MS,
            restingOpacity = { (100f - (currentConfig?.transparencyPercentage ?: 0)) / 100f },
            // Dismissed through the store. Hiding the window directly left the application still
            // believing the popup was open.
            onExpired = { onDismiss() }
        )
    }

    // flags
    private var isDragging = false
    private var isResizing = false

    /**
     * Mirrored into the popup helper, whose idle-hide and pointer tracking both consult it.
     * Two copies of "is this pinned" drifting apart means a pinned popup closing itself anyway.
     */
    private var isPinned = false
        set(value) {
            field = value
            popup.isPinned = value
        }
    private var wasManuallyMoved = false
    private var currentConfig: DialogConfig? = null

    private var lastRenderedText: String? = null

    /** True while the popup has been asked for but is waiting for something worth showing. */
    private var pendingShow = false

    /** The trigger this popup last reacted to; see [QuickTranslateDialogState.triggerCount]. */
    private var lastTriggerCount = 0

    init {
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
            // Styled rather than cleared: `borderWidth` addresses the look and feel's own border,
            // so it is reapplied on a theme change and there is nothing to restore. Replacing the
            // border outright would leave this style with no border to act on.
            putClientProperty(
                FlatClientProperties.STYLE,
                "borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; innerOutlineWidth: 0;"
            )
            // JViewport rejects any border but null, and never installs one of its own.
            viewport.border = null

            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        // Header, then the hairline loading bar, then the text. The bar reserves its height even
        // when idle, so a reload does not nudge the translation down and back up again.
        mainPanel.add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(topPanel, BorderLayout.CENTER)
                add(loadingBar, BorderLayout.SOUTH)
            },
            BorderLayout.NORTH
        )
        mainPanel.add(textScrollPane, BorderLayout.CENTER)
        // Below the translation, above nothing: an aside, not part of the result.
        mainPanel.add(definitionStrip, BorderLayout.SOUTH)

        setupWindowBehavior(topPanel)
        UIManager.addPropertyChangeListener(themeListener)
        updatePinButtonStyle(isPinned)
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

                // Nothing to show yet, so nothing is shown. Opening now would put a popup on
                // screen sized to an empty string — a sliver that then jumps to full size when
                // the translation lands. The loading indicator covers the wait instead, and the
                // popup appears once, already the right size.
                if (state.isLoading && state.translatedText.isBlank()) {
                    pendingShow = true
                    return
                }
                showNow(state)
            } else {
                pendingShow = false
                hideDialog()
            }
            return
        }

        if (!isVisible) {
            // A deferred open, now that the result has arrived — or failed, which also deserves
            // to be shown rather than left waiting forever.
            if (pendingShow && state.isVisible && (!state.isLoading || state.translatedText.isNotBlank())) {
                currentConfig = state.config
                updateContent(state)
                showNow(state)
            }
            return
        }

        val pinStateChanged = this.isPinned != state.isPinned
        val retriggered = lastTriggerCount != state.triggerCount
        lastTriggerCount = state.triggerCount

        updateContent(state)

        if (pinStateChanged) handlePinState(state)

        // Asked for again while already open: refresh in place, come back to full opacity, and
        // restart the countdown. Re-sized for the new text, since it is a different translation.
        if (retriggered) {
            fadeTo(1f, FADE_MS)
            if (!isResizing && !isDragging) applySize(state.translatedText)
            popup.noteActivity()
            toFront()
        }

        // only refresh font when user changed it
        if (!isResizing && !isDragging) {
            outputTextArea.updateFontsAndRescanDocument(
                newPrimary = state.config.font.toFont(),
                newFallback = state.config.fallbackFont.toFont()
            )
        }
    }

    /** Sizes the popup for the text it is about to show, then puts it on screen. */
    private fun showNow(state: QuickTranslateDialogState) {
        pendingShow = false
        wasManuallyMoved = false
        lastTriggerCount = state.triggerCount
        applySize(state.translatedText)
        applyPosition()
        showDialog()
    }

    // Full content sync
    private fun updateContent(state: QuickTranslateDialogState) {
        this.isPinned = state.isPinned
        // Only while something is already on screen: before that the popup is withheld and the
        // standalone loading indicator covers the wait.
        loadingBar.isLoading = state.isLoading && isVisible
        // Only for single words; the state carries it empty otherwise, so the strip hides itself.
        definitionStrip.render(state.definition)

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

        pinButton.toolTipText = if (state.isPinned) state.strings.unpinTooltip else state.strings.pinTooltip
        listenButton.toolTipText = state.strings.listenTooltip
        copyButton.toolTipText = state.strings.copyTooltip
        closeButton.toolTipText = state.strings.closeTooltip

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

    /**
     * Delegated to the shared helper, which also drops a bug that lived only here: the old guard
     * returned early while a fade was still running, so a fade back to transparency was silently
     * discarded whenever the mouse entered and left within one animation. The dictionary popup
     * had this fixed; this one never did.
     */
    private fun fadeTo(targetOpacity: Float, durationMs: Int) = popup.fadeTo(targetOpacity, durationMs)

    private fun setOpacityIfDifferent(value: Float) {
        if (abs(opacity - value) > 0.01f) opacity = value
    }

    private fun showDialog() {
        // apply initial opacity from config (without animation)
        val transparency = currentConfig?.transparencyPercentage ?: 0
        opacity = (100f - transparency) / 100f

        // Set before showing: changing focusableWindowState on a window already on screen makes
        // AWT discard and rebuild the native peer, which flickers and can drop focus.
        focusableWindowState = true
        isVisible = true
        installAwtMouseListener()
        // Not if the pointer is already inside it -- the popup opens at the cursor, so it often
        // is, and starting the countdown then hides the popup out from under the reader.
        if (!isPinned && !popup.isPointerOver) startIdleHide()
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

    private fun startIdleHide() = popup.startIdleHide()

    private fun stopIdleHide() = popup.stopIdleHide()


    private fun installAwtMouseListener() = popup.installPointerTracking()

    private fun uninstallAwtMouseListener() = popup.uninstallPointerTracking()


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

        // Use the monitor where the mouse cursor currently lives, not where the dialog
        // happens to be placed — prevents wrong-monitor bounds on multi-monitor setups.
        val gc = MouseInfo.getPointerInfo()?.device?.defaultConfiguration ?: graphicsConfiguration
        val screenBounds = gc.bounds
        // Bounded by a readable line length first and the screen second. A share of the screen
        // alone stretches one sentence across half a wide monitor, which is hard to read for the
        // same reason a book is not printed edge to edge.
        val maxWidth = PopupSizing.maxTextWidth(
            measurePane.getFontMetrics(outputTextArea.font),
            screenBounds
        )
        val maxHeight = PopupSizing.maxHeight(screenBounds)

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

            // Derive screen bounds from the monitor the mouse is on, not the dialog's current
            // monitor — ensures correct clamping on multi-monitor setups (A-10).
            val gc = MouseInfo.getPointerInfo()?.device?.defaultConfiguration ?: graphicsConfiguration
            val screenBounds = gc.bounds
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
            dividedPanels += this to { BorderFactory.createMatteBorder(0, 0, 0, 1, borderColor) }
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
            dividedPanels += this to { BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor) }
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
                UIManager.removePropertyChangeListener(themeListener)
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
