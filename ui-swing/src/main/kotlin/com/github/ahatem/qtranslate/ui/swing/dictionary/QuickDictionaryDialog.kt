package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.*
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ComponentMover
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ComponentResizer
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.*
import java.awt.event.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.math.abs

/**
 * Floating, always-on-top dictionary popup.
 *
 * Mirrors the QuickTranslateDialog pattern:
 * - MODELESS JDialog, undecorated, always-on-top
 * - Auto-positions near mouse cursor
 * - Auto-hides after idle (unless pinned)
 * - Fade animation on show/hide
 * - Draggable via header bar (ComponentMover)
 * - Resizable via border handles (ComponentResizer)
 * - Pin button to keep it visible
 */
class QuickDictionaryDialog(
    private val owner: Frame,
    private val iconManager: IconManager
) : JDialog(owner, ModalityType.MODELESS), Renderable<QuickDictionaryDialogState> {

    private companion object {
        const val RESIZE_HANDLE_SIZE = 8
        const val PINNED_BORDER_WIDTH = 4
        const val FADE_MS = 160
        const val FADE_STEPS = 8
        const val IDLE_HIDE_MS = 8000
        const val RESIZE_SAVE_DEBOUNCE_MS = 180
    }

    // Theme colors
    private val borderColor = UIManager.getColor("Component.borderColor")
    private val accentBorderColor = UIManager.getColor("Component.focusedBorderColor")
        ?: UIManager.getColor("Component.accentColor")
        ?: borderColor
    private val toolbarSelectedBg = UIManager.getColor("Button.toolbar.selectedBackground")
    private val toolbarSelectedFg = UIManager.getColor("Button.toolbar.selectedForeground")
    private val labelFg = UIManager.getColor("Label.foreground")
    private val disabledFg = UIManager.getColor("Label.disabledForeground")

    // Header widgets
    private val titleLabel = JLabel("").apply {
        putClientProperty("FlatLaf.styleClass", "h4")
    }
    private val pinButton = createButtonWithIcon(iconManager, "icons/lucide/pin.svg", 14)
    private val closeButton = createButtonWithIcon(iconManager, "icons/lucide/close.svg", 16)

    // Auto-source cycling button — mirrors DictionaryPanel
    private val activeLinkIcon: FlatSVGIcon =
        (iconManager.getIcon("icons/lucide/link-2.svg", 13, 13) as FlatSVGIcon).applyForegroundColorFilter()
    private val offUnlinkIcon: FlatSVGIcon =
        (iconManager.getIcon("icons/lucide/unlink.svg", 13, 13) as FlatSVGIcon).apply {
            colorFilter = FlatSVGIcon.ColorFilter { UIManager.getColor("Label.disabledForeground") }
        }
    private val autoSourceButton = JButton().apply {
        putClientProperty("JButton.buttonType", "toolBarButton")
        isFocusable = false
        iconTextGap = 4
        addActionListener {
            val state = currentState ?: return@addActionListener
            val next = when (state.autoSource) {
                DictionaryAutoSource.OFF        -> DictionaryAutoSource.TRANSLATED
                DictionaryAutoSource.TRANSLATED -> DictionaryAutoSource.SOURCE
                DictionaryAutoSource.SOURCE     -> DictionaryAutoSource.OFF
            }
            state.onAutoSourceChanged(next)
        }
    }

    // Service picker
    private var updatingFromState = false
    private val serviceCombo = JComboBox<ServiceInfo>().apply {
        putClientProperty("JComboBox.isTableCellEditor", true)
        setRenderer { _, value, _, _, _ -> JLabel(value?.name ?: "") }
    }
    private val serviceRow = JPanel(BorderLayout(6, 0)).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        add(serviceCombo, BorderLayout.CENTER)
        isVisible = false
    }

    // Search row
    private val searchField = JTextField()
    private val lookupButton = JButton()

    // Status labels
    private val hintLabel = JLabel("", SwingConstants.CENTER).apply {
        foreground = disabledFg
    }
    private val loadingLabel = JLabel("", SwingConstants.CENTER).apply {
        foreground = disabledFg
    }

    // Results
    private val resultView = DictionaryResultView()
    private val cardPanel = JPanel(CardLayout())

    // Word chips
    private val chips = DictionaryChipController { word ->
        searchField.text = word
        currentState?.onLookup?.invoke(word)
    }

    // State
    private var isPinned = false
    private var wasManuallyMoved = false
    private var isDragging = false
    private var isResizing = false
    private var currentState: QuickDictionaryDialogState? = null

    // Timers
    private val fadeLock = AtomicBoolean(false)
    private var fadeTimer: Timer? = null
    private var idleHideTimer: Timer? = null
    private var resizeSaveTimer: Timer? = null

    // Mouse over detection
    private var awtMouseListener: AWTEventListener? = null
    private var isMouseOver = false

    private val topPanel: JPanel
    private val mainPanel: JPanel

    init {
        isUndecorated = true
        isAlwaysOnTop = true
        minimumSize = Dimension(320, 200)
        focusableWindowState = false

        val wrapperPanel = JPanel(BorderLayout()).apply {
            val borderSize = RESIZE_HANDLE_SIZE / 2
            border = EmptyBorder(borderSize, borderSize, borderSize, borderSize)
            isOpaque = false
        }
        contentPane = wrapperPanel

        mainPanel = JPanel(BorderLayout())
        wrapperPanel.add(mainPanel, BorderLayout.CENTER)

        topPanel = createTopPanel()
        val searchPanel = createSearchPanel()
        val contentArea = createContentArea()

        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(8, 8, 0, 8)
            add(searchPanel, BorderLayout.CENTER)
        }, BorderLayout.CENTER)

        // Restructure: top=header, center=body (search+chips+results)
        mainPanel.removeAll()
        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(contentArea, BorderLayout.CENTER)

        setupWindowBehavior()
        updatePinButtonStyle(false)
    }

    override fun render(state: QuickDictionaryDialogState) {
        val wasVisible = isVisible
        val visibilityChanged = wasVisible != state.isVisible

        if (visibilityChanged) {
            if (state.isVisible) {
                currentState = state
                wasManuallyMoved = false
                updateContent(state)
                // Pre-fill search field with the word that triggered the popup.
                if (state.lookedUpWord.isNotBlank()) {
                    setSearchWord(state.lookedUpWord)
                }
                applySize(state.config)
                applyPosition(state.config)
                showDialog()
            } else {
                hideDialog()
            }
            return
        }

        if (!isVisible) return

        val pinChanged = this.isPinned != state.isPinned
        currentState = state
        updateContent(state)
        if (pinChanged) handlePinState(state)
    }

    private fun updateContent(state: QuickDictionaryDialogState) {
        isPinned = state.isPinned
        titleLabel.text = state.strings.title

        lookupButton.text = state.strings.lookupButtonLabel
        loadingLabel.text = state.strings.loadingMessage
        pinButton.toolTipText = if (state.isPinned) state.strings.unpinTooltip else state.strings.pinTooltip
        closeButton.toolTipText = state.strings.closeTooltip

        // Sync auto-source cycling button
        val autoLabel = when (state.autoSource) {
            DictionaryAutoSource.OFF        -> state.autoSourceOffLabel
            DictionaryAutoSource.TRANSLATED -> state.autoSourceTranslatedLabel
            DictionaryAutoSource.SOURCE     -> state.autoSourceSourceLabel
        }
        autoSourceButton.text = autoLabel
        autoSourceButton.toolTipText = autoLabel
        val autoActive = state.autoSource != DictionaryAutoSource.OFF
        autoSourceButton.icon = if (autoActive) activeLinkIcon else offUnlinkIcon
        autoSourceButton.foreground = if (autoActive)
            UIManager.getColor("Component.accentColor") ?: UIManager.getColor("Button.foreground")
        else
            UIManager.getColor("Label.disabledForeground")

        hintLabel.text = when {
            state.hasFailed -> state.strings.errorMessage
            state.lookedUpWord.isNotBlank() && !state.isLoading && state.entries.isEmpty() ->
                state.strings.notFoundMessage
            else -> state.strings.hintMessage
        }

        // Chip sync
        if (chips.hasChips && !state.isLoading && state.lookedUpWord.isNotBlank()) {
            if (state.entries.isEmpty() && !state.hasFailed) {
                chips.removeChipForWord(state.lookedUpWord)
            } else if (state.entries.isNotEmpty()) {
                chips.syncSelection(state.lookedUpWord)
            }
        }

        // Service picker
        updatingFromState = true
        try {
            val dicts = state.availableDictionaries
            serviceRow.isVisible = dicts.isNotEmpty()
            if (dicts.isNotEmpty()) {
                if (serviceCombo.itemCount != dicts.size ||
                    (0 until serviceCombo.itemCount).any { serviceCombo.getItemAt(it) != dicts[it] }
                ) {
                    serviceCombo.removeAllItems()
                    dicts.forEach { serviceCombo.addItem(it) }
                }
                val toSelect = dicts.find { it.id == state.selectedDictionaryId }
                if (toSelect != null && serviceCombo.selectedItem != toSelect) {
                    serviceCombo.selectedItem = toSelect
                }
            }
        } finally {
            updatingFromState = false
        }

        // Card
        val card = when {
            state.isLoading -> "loading"
            state.entries.isNotEmpty() -> "results"
            else -> "hint"
        }
        (cardPanel.layout as CardLayout).show(cardPanel, card)

        if (state.entries.isNotEmpty()) {
            resultView.render(
                entries = state.entries,
                synonymsLabel = state.strings.synonymsLabel,
                onSynonymClicked = { word ->
                    chips.clear()
                    searchField.text = word
                    state.onLookup(word)
                }
            )
        }
    }

    fun setSearchWord(word: String) {
        searchField.text = word
        if (!word.contains(Regex("[,\\s]"))) chips.clear()
    }

    private fun triggerLookup() {
        val input = searchField.text.trim()
        if (input.isBlank()) return
        val words = chips.parseWords(input)
        if (words.size > 1) {
            chips.setup(words)
            currentState?.onLookup?.invoke(words.first())
        } else {
            chips.clear()
            currentState?.onLookup?.invoke(input)
        }
    }

    // -----------------------------------------------------------------------
    // Layout builders
    // -----------------------------------------------------------------------

    private fun createTopPanel(): JPanel {
        pinButton.apply {
            putClientProperty("JButton.buttonType", "toolBarButton")
            isFocusable = false
            addActionListener { currentState?.onPinToggled?.invoke() }
        }
        closeButton.apply {
            putClientProperty("JButton.buttonType", "toolBarButton")
            isFocusable = false
            addActionListener { currentState?.onClose?.invoke() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    background = UIManager.getColor("InternalFrame.closeHoverBackground")
                    foreground = UIManager.getColor("InternalFrame.closeHoverForeground")
                    isContentAreaFilled = true
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

        val rightPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(autoSourceButton)
            add(Box.createRigidArea(Dimension(4, 0)))
            add(pinButton)
            add(Box.createRigidArea(Dimension(4, 0)))
            add(closeButton)
        }

        return JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                EmptyBorder(6, 10, 6, 6)
            )
            add(titleLabel, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.LINE_END)
        }
    }

    private fun createSearchPanel(): JPanel {
        searchField.addActionListener { triggerLookup() }
        lookupButton.addActionListener { triggerLookup() }
        serviceCombo.addActionListener {
            if (!updatingFromState) {
                val selected = serviceCombo.selectedItem as? ServiceInfo ?: return@addActionListener
                currentState?.onDictionarySelected?.invoke(selected.id)
            }
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(serviceRow, BorderLayout.NORTH)
            add(JPanel(BorderLayout(6, 0)).apply {
                isOpaque = false
                add(searchField, BorderLayout.CENTER)
                add(lookupButton, BorderLayout.LINE_END)
            }, BorderLayout.CENTER)
        }
    }

    private fun createContentArea(): JPanel {
        cardPanel.add(hintLabel, "hint")
        cardPanel.add(loadingLabel, "loading")
        cardPanel.add(resultView, "results")

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(8, 8, 8, 8)
            add(createSearchPanel(), BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(chips.scrollPane, BorderLayout.NORTH)
                add(cardPanel, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
    }

    // -----------------------------------------------------------------------
    // Pin / visibility management
    // -----------------------------------------------------------------------

    private fun handlePinState(state: QuickDictionaryDialogState) {
        isPinned = state.isPinned
        updatePinButtonStyle(state.isPinned)
        if (state.isPinned) {
            stopIdleHide()
            fadeTo(1f, FADE_MS)
        } else {
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
                BorderFactory.createEmptyBorder(emptyBorderWidth, emptyBorderWidth, emptyBorderWidth, emptyBorderWidth)
            )
        }
        pinButton.repaint()
    }

    // -----------------------------------------------------------------------
    // Show / hide / fade
    // -----------------------------------------------------------------------

    private fun showDialog() {
        opacity = 1f
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
        val pos = location
        val sz = size
        currentState?.onSavePosition?.invoke(Position(pos.x.coerceAtLeast(0), pos.y.coerceAtLeast(0)))
        currentState?.onSaveSize?.invoke(Size(sz.width, sz.height))
    }

    private fun startIdleHide() {
        idleHideTimer?.stop()
        idleHideTimer = Timer(IDLE_HIDE_MS) { event ->
            if (!isPinned) {
                fadeTo(0f, FADE_MS)
                Timer(FADE_MS + 20) {
                    if (!isPinned) currentState?.onClose?.invoke()
                    (it.source as Timer).stop()
                }.apply { isRepeats = false; start() }
            }
            (event.source as Timer).stop()
        }.apply { isRepeats = false; start() }
    }

    private fun stopIdleHide() {
        idleHideTimer?.stop()
    }

    private fun fadeTo(targetOpacity: Float, durationMs: Int) {
        if (fadeLock.get()) return
        fadeLock.set(true)
        fadeTimer?.stop()
        val start = opacity
        val steps = FADE_STEPS.coerceAtLeast(1)
        val stepDelay = (durationMs / steps).coerceAtLeast(10)
        var step = 0
        fadeTimer = Timer(stepDelay) {
            step++
            val t = step.toFloat() / steps
            val value = start + (targetOpacity - start) * t
            if (abs(opacity - value) > 0.01f) opacity = value
            if (step >= steps) {
                (it.source as Timer).stop()
                fadeLock.set(false)
            }
        }.apply { isRepeats = true; start() }
    }

    // -----------------------------------------------------------------------
    // Mouse over detection
    // -----------------------------------------------------------------------

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
                        if (!isPinned) startIdleHide()
                    }
                } else {
                    if (isMouseOver && !isPinned) startIdleHide()
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(
            awtMouseListener,
            AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK
        )
    }

    private fun uninstallAwtMouseListener() {
        awtMouseListener?.let {
            Toolkit.getDefaultToolkit().removeAWTEventListener(it)
            awtMouseListener = null
            isMouseOver = false
        }
    }

    // -----------------------------------------------------------------------
    // Size / position
    // -----------------------------------------------------------------------

    private fun applySize(config: QuickDictionaryConfig) {
        size = config.lastKnownSize.toDimension()
    }

    private fun applyPosition(config: QuickDictionaryConfig) {
        if (wasManuallyMoved) return
        val screenBounds = graphicsConfiguration?.bounds ?: run {
            setLocationRelativeTo(owner)
            return
        }
        when {
            !config.autoPositionEnabled -> {
                location = config.lastKnownPosition.toPoint()
            }
            !config.positionNearMouse -> {
                // Auto-triggered from translation — position adjacent to the owner window
                // so the popup doesn't appear wherever the mouse happens to be.
                val ownerBounds = owner.bounds
                var x = ownerBounds.x + ownerBounds.width + 8
                var y = ownerBounds.y + (ownerBounds.height - height) / 2
                // If no room to the right, try to the left.
                if (x + width > screenBounds.x + screenBounds.width) {
                    x = ownerBounds.x - width - 8
                }
                x = x.coerceIn(screenBounds.x, (screenBounds.x + screenBounds.width - width).coerceAtLeast(screenBounds.x))
                y = y.coerceIn(screenBounds.y, (screenBounds.y + screenBounds.height - height).coerceAtLeast(screenBounds.y))
                setLocation(x, y)
            }
            else -> {
                // Near mouse cursor (default: hotkey trigger)
                val mouseLocation = MouseInfo.getPointerInfo()?.location ?: run {
                    setLocationRelativeTo(owner)
                    return
                }
                val offsetX = 12
                val offsetY = 12
                var x = mouseLocation.x + offsetX
                var y = mouseLocation.y + offsetY
                if (x + width > screenBounds.x + screenBounds.width) x = mouseLocation.x - width - offsetX
                if (y + height > screenBounds.y + screenBounds.height) y = mouseLocation.y - height - offsetY
                x = x.coerceAtLeast(screenBounds.x)
                y = y.coerceAtLeast(screenBounds.y)
                setLocation(x, y)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Drag / resize wiring
    // -----------------------------------------------------------------------

    private fun setupWindowBehavior() {
        val dragInsets = Insets(RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE)

        ComponentMover.builder()
            .destinationComponent(this)
            .build()
            .register(topPanel)

        ComponentResizer.builder()
            .dragInsets(dragInsets)
            .minimumSize(minimumSize)
            .onResizeStart {
                isResizing = true
                stopIdleHide()
                fadeTo(1f, FADE_MS)
            }
            .onResizeEnd {
                isResizing = false
                resizeSaveTimer?.stop()
                resizeSaveTimer = Timer(RESIZE_SAVE_DEBOUNCE_MS) {
                    currentState?.onSaveSize?.invoke(size.toSize())
                    (it.source as Timer).stop()
                }.apply { isRepeats = false; start() }
                if (!isPinned) startIdleHide()
            }
            .build()
            .register(this)

        val dragListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                isDragging = true
                wasManuallyMoved = true
                stopIdleHide()
                fadeTo(1f, FADE_MS / 2)
            }
            override fun mouseReleased(e: MouseEvent) {
                isDragging = false
                val pos = location
                currentState?.onSavePosition?.invoke(
                    Position(pos.x.coerceAtLeast(0), pos.y.coerceAtLeast(0))
                )
                if (!isPinned) startIdleHide()
            }
        }
        topPanel.addMouseListener(dragListener)
        topPanel.addMouseMotionListener(dragListener)
        addMouseListener(dragListener)
        addMouseMotionListener(dragListener)

        addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) { if (!isPinned) startIdleHide() }
            override fun windowLostFocus(e: WindowEvent?) { if (!isPinned) startIdleHide() }
        })

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) { currentState?.onClose?.invoke() }
            override fun windowClosed(e: WindowEvent) { uninstallAwtMouseListener() }
        })

        rootPane.registerKeyboardAction(
            { if (!isPinned) currentState?.onClose?.invoke() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )
    }
}
