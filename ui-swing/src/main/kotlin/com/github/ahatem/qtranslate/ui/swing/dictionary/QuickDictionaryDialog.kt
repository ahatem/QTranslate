package com.github.ahatem.qtranslate.ui.swing.dictionary

import com.formdev.flatlaf.util.UIScale
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.*
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ComponentMover
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.ComponentResizer
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.FloatingPopupBehavior
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.InlineLoadingBar
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.math.abs
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

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
    /**
     * The main window, used only to position against. It is deliberately NOT this dialog's
     * owner -- see FloatingPopupBehavior for why a tray application must not own these.
     */
    private val owner: Frame,
    private val iconManager: IconManager
) : JDialog(null as Frame?, ModalityType.MODELESS), Renderable<QuickDictionaryDialogState> {

    private companion object {
        const val RESIZE_HANDLE_SIZE = 8
        const val PINNED_BORDER_WIDTH = 4
        const val FADE_MS = 160
        const val FADE_STEPS = 8
        const val IDLE_HIDE_MS = 8000
        const val RESIZE_SAVE_DEBOUNCE_MS = 180
    }

    // Theme colors
    // Accessors rather than fields. This dialog is built once and lives for the whole session, so
    // a colour captured here would be the one the theme had at startup, and every border and
    // dimmed label would keep it after a theme switch. See refreshTheme.
    private val borderColor: Color? get() = UIManager.getColor("Component.borderColor")
    private val accentBorderColor: Color?
        get() = UIManager.getColor("Component.focusedBorderColor")
            ?: UIManager.getColor("Component.accentColor")
            ?: borderColor
    private val toolbarSelectedBg: Color? get() = UIManager.getColor("Button.toolbar.selectedBackground")
    private val toolbarSelectedFg: Color? get() = UIManager.getColor("Button.toolbar.selectedForeground")
    private val labelFg: Color? get() = UIManager.getColor("Label.foreground")
    private val disabledFg: Color? get() = UIManager.getColor("Label.disabledForeground")

    /** Held so it can be detached; also the reason this is not an inline lambda. */
    private val themeListener = java.beans.PropertyChangeListener { event ->
        if (event.propertyName == "lookAndFeel") SwingUtilities.invokeLater { refreshTheme() }
    }

    /** The header, kept so its divider can be recoloured when the theme changes. */
    private var headerPanel: JPanel? = null

    // Header widgets
    private val titleLabel = JLabel("").apply {
        putClientProperty("FlatLaf.styleClass", "h4")
    }
    private val pinButton = createButtonWithIcon(iconManager, Icons.PIN, 14)
    private val closeButton = createButtonWithIcon(iconManager, Icons.CLOSE, 16)

    // Auto-source cycling button — mirrors DictionaryPanel
    private val activeLinkIcon: FlatSVGIcon =
        (iconManager.getIcon(Icons.NETWORK, 13, 13) as FlatSVGIcon).applyForegroundColorFilter()
    private val offUnlinkIcon: FlatSVGIcon =
        (iconManager.getIcon(Icons.UNPIN, 13, 13) as FlatSVGIcon).apply {
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
    private val loadingBar = InlineLoadingBar()
    private val resultView = DictionaryResultView(iconManager)
    private val cardPanel = JPanel(CardLayout())

    // Word chips
    private val chips = DictionaryChipController { word ->
        searchField.text = word
        currentState?.onLookup?.invoke(word)
    }

    /**
     * Window flags, fading, idle-hide, pointer presence — shared with the other two popups.
     *
     * Declared ahead of [isPinned], whose setter writes into it: Kotlin initialises properties in
     * declaration order, and the other way round leaves a window in which assigning isPinned
     * would dereference null.
     */
    private val popup = FloatingPopupBehavior(
        window = this,
        owner = owner,
        minimumSize = Dimension(PopupSizing.minWidth(), UIScale.scale(240)),
        pinnedBorderWidth = PINNED_BORDER_WIDTH,
        resizeHandle = RESIZE_HANDLE_SIZE
    ).apply {
        configureIdleHide(
            delayMs = { (currentState?.config?.idleTimeoutSeconds ?: 8) * 1000 },
            fadeMs = FADE_MS,
            restingOpacity = { (100f - (currentState?.config?.transparencyPercentage ?: 0)) / 100f },
            // Dismissed through the store, so the application does not go on believing the popup
            // is open after it has gone.
            onExpired = { currentState?.onClose?.invoke() }
        )
    }

    // State
    /**
     * Mirrored into the popup helper on every change.
     *
     * The helper's idle-hide and pointer tracking both consult it, and two copies of "is this
     * pinned" that drift apart mean a pinned popup that closes itself anyway.
     */
    private var isPinned = false
        set(value) {
            field = value
            popup.isPinned = value
        }
    private var wasManuallyMoved = false
    private var isDragging = false
    private var isResizing = false
    private var currentState: QuickDictionaryDialogState? = null

    // Timers
    private var resizeSaveTimer: Timer? = null

    private val topPanel: JPanel
    private val mainPanel: JPanel

    init {
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
        mainPanel.add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(topPanel, BorderLayout.CENTER)
                add(loadingBar, BorderLayout.SOUTH)
            },
            BorderLayout.NORTH
        )
        mainPanel.add(contentArea, BorderLayout.CENTER)

        setupWindowBehavior()
        UIManager.addPropertyChangeListener(themeListener)
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
        val retriggered = lastTriggerCount != state.triggerCount
        lastTriggerCount = state.triggerCount

        currentState = state
        updateContent(state)
        if (pinChanged) handlePinState(state)

        // The user asked for this popup again while it was already open. Refresh in place and
        // give them the full countdown back — but only on a real trigger, since render runs for
        // every unrelated state change and resetting on all of them would disable auto-hide.
        if (retriggered) {
            fadeTo(1f, FADE_MS)
            noteUserActivity()
        }
    }

    /** The trigger this popup last reacted to; see [QuickDictionaryDialogState.triggerCount]. */
    private var lastTriggerCount = 0

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

        // Definitions already on screen stay there while the next lookup runs, with the hairline
        // bar carrying the "working" signal instead. Swapping to the loading card would take away
        // what the reader was in the middle of and give them a spinner in exchange.
        loadingBar.isLoading = state.isLoading

        val card = when {
            state.isLoading && state.entries.isEmpty() -> "loading"
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
                },
                listenTooltip = state.strings.listenTooltip,
                stopTooltip = state.strings.stopListeningTooltip,
                onListen = state.onListen,
                onStopListening = state.onStopListening
            )
        }
        resultView.setSpeaking(state.isTtsPlaying)
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
            border = headerBorder()
            add(titleLabel, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.LINE_END)
            headerPanel = this
        }
    }

    private fun createSearchPanel(): JPanel {
        searchField.addActionListener { triggerLookup() }
        lookupButton.addActionListener { triggerLookup() }

        // Typing is the clearest possible sign the popup is still wanted, and it used to count
        // for nothing: with the pointer parked outside, a word typed slowly disappeared under the
        // person typing it.
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) = noteUserActivity()
        })
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
                BorderFactory.createEmptyBorder(emptyBorderWidth, emptyBorderWidth, emptyBorderWidth, emptyBorderWidth)
            )
        }
        pinButton.repaint()
    }

    // -----------------------------------------------------------------------
    // Show / hide / fade
    // -----------------------------------------------------------------------

    private fun applyTransparency() {
        val pct = currentState?.config?.transparencyPercentage ?: 0
        val target = (100f - pct) / 100f
        fadeTo(target, FADE_MS)
    }

    private fun showDialog() {
        // set initial opacity from config before making visible (no animation on first show)
        val pct = currentState?.config?.transparencyPercentage ?: 0
        opacity = (100f - pct) / 100f
        // Set before showing. Changing focusableWindowState on a window already on screen makes
        // AWT discard the native peer and build a new one, which flickers and can drop the focus
        // the popup has just taken.
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
        val pos = location
        val sz = size
        currentState?.onSavePosition?.invoke(Position(pos.x.coerceAtLeast(0), pos.y.coerceAtLeast(0)))
        currentState?.onSaveSize?.invoke(Size(sz.width, sz.height))
    }

    private fun startIdleHide() = popup.startIdleHide()

    private fun stopIdleHide() = popup.stopIdleHide()

    /** Restarts the idle countdown because the user is doing something — typing counts. */
    private fun noteUserActivity() = popup.noteActivity()

    private fun headerBorder() = BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
        EmptyBorder(6, 10, 6, 6)
    )

    /**
     * Re-applies every colour this dialog painted itself with.
     *
     * A border keeps whatever colour it was handed, and a new look and feel does not revisit it,
     * so without this the popup goes on showing the previous theme's divider and dimmed text
     * against the new background — the same fault the service selector had.
     */
    private fun refreshTheme() {
        headerPanel?.border = headerBorder()
        hintLabel.foreground = disabledFg
        loadingLabel.foreground = disabledFg
        updatePinButtonStyle(isPinned)
        revalidate()
        repaint()
    }

    private fun fadeTo(targetOpacity: Float, durationMs: Int) = popup.fadeTo(targetOpacity, durationMs)

    // -----------------------------------------------------------------------
    // Mouse over detection
    // -----------------------------------------------------------------------

    private fun installAwtMouseListener() = popup.installPointerTracking()

    private fun uninstallAwtMouseListener() = popup.uninstallPointerTracking()


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
            override fun windowClosed(e: WindowEvent) {
                uninstallAwtMouseListener()
                UIManager.removePropertyChangeListener(themeListener)
            }
        })

        rootPane.registerKeyboardAction(
            { if (!isPinned) currentState?.onClose?.invoke() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )
    }
}
