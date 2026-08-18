package com.github.ahatem.qtranslate.ui.swing.imagesearch

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.icons.FlatSearchIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.imagesearch.ImageResult
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.Position
import com.github.ahatem.qtranslate.core.settings.data.Size
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.util.toDimension
import com.github.ahatem.qtranslate.ui.swing.shared.util.PopupSizing
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.FloatingPopupBehavior
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.InlineLoadingBar
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Image
import java.awt.Insets
import java.awt.MouseInfo
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

/**
 * Floating popup showing pictures for a term.
 *
 * The visual sibling of the quick dictionary popup — undecorated, always on top, movable,
 * resizable, dismissed with Escape — because it answers the same question from the same gesture
 * on the same selection, and behaving differently would make it feel like a different app.
 *
 * It deliberately does *not* copy that dialog's idle-hide timer. A definition is read in a couple
 * of seconds; a grid of pictures is looked over, compared, and clicked through, and a popup that
 * vanishes mid-comparison would be worse than one the reader closes themselves.
 */
class ImageSearchDialog(
    /**
     * The main window, used only to position against. It is deliberately NOT this dialog's
     * owner -- see FloatingPopupBehavior for why a tray application must not own these.
     */
    private val owner: Frame,
    private val iconManager: IconManager
) : JDialog(null as Frame?, ModalityType.MODELESS), Renderable<ImageSearchDialogState> {

    private companion object {
        const val RESIZE_HANDLE_SIZE = 8
        const val PINNED_BORDER_WIDTH = 4
        /**
         * The narrowest a tile may get before the picture in it stops being readable.
         *
         * Columns are derived from this rather than fixed, so a narrow popup shows one usable
         * image instead of three unusable ones. A diagram — which is the kind of picture this
         * feature exists to show — is a smudge below roughly this width.
         */
        const val MIN_TILE_WIDTH = 180
        const val MAX_COLUMNS = 4
        const val TILE_HEIGHT = 124
        const val GRID_GAP = 6
    }

    // Read on each use rather than captured once: a colour held in a field keeps the value the
    // theme had when this dialog was built, and these popups are created once and live for the
    // whole session, so after a theme switch they would go on painting the old theme's grey.
    private val borderColor: Color get() = UIManager.getColor("Component.borderColor") ?: Color.GRAY
    private val accentColor: Color
        get() = UIManager.getColor("Component.focusedBorderColor")
            ?: UIManager.getColor("Component.accentColor")
            ?: borderColor

    private val thumbnails = ThumbnailLoader()


    private val titleLabel = JLabel("").apply { putClientProperty("FlatLaf.styleClass", "h4") }
    private val pinButton = createButtonWithIcon(iconManager, Icons.PIN, 14)
    private val closeButton = createButtonWithIcon(iconManager, Icons.CLOSE, 16)

    /**
     * A search field in the look and feel's own idiom rather than a bare text box.
     *
     * FlatLaf draws the magnifier and the clear button itself from these properties, so they
     * follow the theme and the scale factor without any icon handling here. The clear button only
     * appears when there is something to clear.
     */
    private val searchField = JTextField().apply {
        putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, FlatSearchIcon())
        putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true)
        addActionListener { currentState?.onSearch?.invoke(text.trim()) }
    }

    private val hintLabel = JLabel("", SwingConstants.CENTER).apply {
        foreground = UIManager.getColor("Label.disabledForeground")
        border = EmptyBorder(24, 12, 24, 12)
    }

    private val grid = JPanel(GridLayout(0, 1, GRID_GAP, GRID_GAP)).apply {
        border = EmptyBorder(8, 8, 8, 8)
    }

    private val scroll = JScrollPane(grid).apply {
        border = null
        viewportBorder = null
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = 16
    }

    private val loadingBar = InlineLoadingBar()

    private val body = JPanel(BorderLayout())

    /** The enlarged view, or null when the grid is showing. */
    private var preview: ImageResult? = null

    /** Kept as a field because it is both the drag handle and the title row. */
    private var header: JComponent

    private var currentState: ImageSearchDialogState? = null
    private var isPinned = false

    /**
     * The results the grid was last built from.
     *
     * Rebuilding tiles on every render would restart every thumbnail fetch and lose the reader's
     * scroll position, and render runs on any state change — including ones this dialog does not
     * care about.
     */
    private var renderedResults: List<ImageResult> = emptyList()

    /** The trigger this popup last reacted to; see [ImageSearchDialogState.triggerCount]. */
    private var lastTriggerCount = 0

    /** Undecorated, always on top, draggable, resizable, Escape-dismissed — shared with the
     *  translate and dictionary popups so all three behave the same as windows. */
    private val popup = FloatingPopupBehavior(
        window = this,
        owner = owner,
        minimumSize = Dimension(PopupSizing.minWidth(), UIScale.scale(300)),
        pinnedBorderWidth = PINNED_BORDER_WIDTH
    )

    init {
        focusableWindowState = true

        pinButton.addActionListener { currentState?.onPinToggled?.invoke() }
        closeButton.addActionListener { currentState?.onClose?.invoke() }

        header = buildHeader()
        contentPane = JPanel(BorderLayout()).apply {
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(header, BorderLayout.CENTER)
                    add(loadingBar, BorderLayout.SOUTH)
                },
                BorderLayout.NORTH
            )
            add(body.apply { add(scroll, BorderLayout.CENTER) }, BorderLayout.CENTER)
        }

        popup.installDrag(header) { position -> currentState?.onSavePosition?.invoke(position) }
        popup.installResize({ size -> currentState?.onSaveSize?.invoke(size) })
        // Escape unwinds one step at a time: out of the enlarged image first, and only then out
        // of the popup. Closing outright would throw away the search as well.
        popup.installEscape {
            if (preview != null) {
                showGrid(); true
            } else {
                currentState?.onClose?.invoke(); true
            }
        }
        popup.installTheme(::refreshTheme)
        popup.applyPinBorder(false)

        installResponsiveColumns()
    }

    /**
     * Re-applies the colours already painted into borders and labels.
     *
     * The accessors above keep *new* components correct; this is for the ones already built, since
     * a border holds the colour it was handed and a theme switch does not revisit it.
     */
    private fun refreshTheme() {
        applyPinStyle(isPinned)
        hintLabel.foreground = UIManager.getColor("Label.disabledForeground")
        // Tiles carry a border and a dimmed credit line, and are cheapest to simply rebuild.
        renderedResults = emptyList()
        currentState?.let { rebuildGridIfChanged(it) }
        revalidate()
        repaint()
    }

    /**
     * Recomputes how many tiles fit across whenever the popup is resized.
     *
     * Driven by the viewport rather than the window so the scrollbar's width is already accounted
     * for; otherwise the last column is cut off exactly when a scrollbar appears.
     */
    private fun installResponsiveColumns() {
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyColumnCount()
        })
    }

    private fun applyColumnCount() {
        val insets = grid.insets
        val available = scroll.viewport.width - insets.left - insets.right
        if (available <= 0) return

        val tile = UIScale.scale(MIN_TILE_WIDTH) + UIScale.scale(GRID_GAP)
        val columns = (available / tile).coerceIn(1, MAX_COLUMNS)

        val layout = grid.layout as GridLayout
        if (layout.columns == columns) return
        layout.columns = columns
        // Rows must follow, or GridLayout keeps the old row count and lays the tiles out to it.
        layout.rows = 0
        grid.revalidate()
        grid.repaint()
    }

    private fun buildHeader(): JComponent {
        val buttons = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(pinButton)
            add(Box.createHorizontalStrut(2))
            add(closeButton)
        }

        val titleRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.CENTER)
            add(buttons, BorderLayout.LINE_END)
        }

        return JPanel(BorderLayout(0, 6)).apply {
            border = EmptyBorder(8, 10, 8, 8)
            add(titleRow, BorderLayout.NORTH)
            add(searchField, BorderLayout.CENTER)
        }
    }

    override fun render(state: ImageSearchDialogState) {
        val visibilityChanged = isVisible != state.isVisible

        if (visibilityChanged) {
            if (state.isVisible) {
                currentState = state
                popup.resetManualMove()
                applyText(state)
                if (state.searchedTerm.isNotBlank() && searchField.text != state.searchedTerm) {
                    searchField.text = state.searchedTerm
                }
                rebuildGridIfChanged(state)
                size = state.config.lastKnownSize.toDimension()
                applyPosition(state.config)
                // Before it is shown: changing opacity on a window already on screen repaints it
                // at the new value, which reads as a flicker on open.
                applyTransparency(state.config)
                isVisible = true
                // Queued rather than requested inline: focus cannot be taken until the window is
                // actually on screen, so asking during the same event does nothing and the field
                // silently fails to accept typing.
                SwingUtilities.invokeLater { searchField.requestFocusInWindow() }
            } else {
                saveGeometry()
                isVisible = false
            }
            return
        }

        if (!isVisible) return

        val pinChanged = isPinned != state.isPinned
        val retriggered = lastTriggerCount != state.triggerCount
        lastTriggerCount = state.triggerCount

        currentState = state
        applyText(state)
        rebuildGridIfChanged(state)
        if (pinChanged) applyPinStyle(state.isPinned)
        // Re-triggered from a new selection: the field should show the word being searched, not
        // the one from last time.
        if (retriggered && state.searchedTerm.isNotBlank() && searchField.text != state.searchedTerm) {
            searchField.text = state.searchedTerm
        }
        // Asked for again while open: bring it back to the front of the user's attention rather
        // than closing and reopening it.
        if (retriggered) toFront()
    }

    private fun applyText(state: ImageSearchDialogState) {
        isPinned = state.isPinned
        loadingBar.isLoading = state.isLoading
        titleLabel.text = state.strings.title
        pinButton.toolTipText = if (state.isPinned) state.strings.unpinTooltip else state.strings.pinTooltip
        closeButton.toolTipText = state.strings.closeTooltip

        hintLabel.text = when {
            state.isLoading -> state.strings.loadingMessage
            state.hasFailed -> state.strings.errorMessage
            state.searchedTerm.isNotBlank() && state.results.isEmpty() -> state.strings.notFoundMessage
            else -> state.strings.hintMessage
        }
    }

    /**
     * Rebuilds the tiles only when the results themselves changed.
     */
    private fun rebuildGridIfChanged(state: ImageSearchDialogState) {
        val showingPlaceholder = state.results.isEmpty()
        if (showingPlaceholder) {
            if (renderedResults.isNotEmpty() || body.componentCount == 0 || body.getComponent(0) !== hintLabel) {
                renderedResults = emptyList()
                grid.removeAll()
                body.removeAll()
                body.add(hintLabel, BorderLayout.CENTER)
                body.revalidate()
                body.repaint()
            }
            return
        }

        if (state.results == renderedResults) return
        renderedResults = state.results

        // Whatever the previous grid was still fetching is now for a term nobody is looking at.
        thumbnails.cancelPending()
        grid.removeAll()
        state.results.forEach { grid.add(tileFor(it, state)) }

        // A new search replaces what the enlarged view was showing, so it returns to the grid
        // rather than leaving an image from the previous term on screen.
        showGrid()
    }

    /**
     * One picture, its caption, and its credit.
     *
     * The credit is shown rather than tucked into a tooltip because the licences these images
     * carry require it to be visible, and a tooltip is not.
     */
    private fun tileFor(result: ImageResult, state: ImageSearchDialogState): JComponent {
        val picture = ScaledImage().apply {
            preferredSize = Dimension(0, UIScale.scale(TILE_HEIGHT))
            border = BorderFactory.createLineBorder(borderColor, 1)
        }

        val caption = ElidingLabel(result.title.orEmpty()).apply {
            putClientProperty("FlatLaf.styleClass", "small")
            toolTipText = result.title
        }

        // The licence alone, dimmed. The author's name is long and varies wildly in length — a
        // grid of them reads as clutter rather than as credit — so it lives in the tooltip and on
        // the page a click opens, which is where a licence expects to be honoured anyway.
        val credit = ElidingLabel(result.license.orEmpty()).apply {
            putClientProperty("FlatLaf.styleClass", "mini")
            foreground = UIManager.getColor("Label.disabledForeground")
            toolTipText = fullCreditFor(result)
        }

        thumbnails.load(result.thumbnailUrl) { image ->
            // The grid may have been rebuilt by a newer search while this was in flight.
            if (picture.parent == null) return@load
            picture.image = image
        }

        return JPanel(BorderLayout(0, 2)).apply {
            add(picture, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(caption, BorderLayout.NORTH)
                    add(credit, BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH
            )
            toolTipText = state.strings.openTooltip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // Enlarged here rather than in a browser. Someone who wants to know what a
                    // word looks like is answered by the picture; sending them to Chrome for it
                    // costs seconds and a context switch, and the source page is still one click
                    // further on for anyone who wants it.
                    showPreview(result)
                }
            })
        }
    }

    /** Fills the popup with one image. */
    private fun showPreview(result: ImageResult) {
        val state = currentState ?: return
        preview = result

        // Sizes itself to the popup as it changes, without rescaling work per resize event.
        val picture = ScaledImage()
        thumbnails.load(result.thumbnailUrl) { image -> picture.image = image }

        val caption = ElidingLabel(result.title.orEmpty()).apply {
            putClientProperty("FlatLaf.styleClass", "h4")
        }
        val credit = ElidingLabel(fullCreditFor(result)).apply {
            putClientProperty("FlatLaf.styleClass", "small")
            foreground = UIManager.getColor("Label.disabledForeground")
            toolTipText = fullCreditFor(result)
        }

        val back = JButton(state.strings.backLabel).apply {
            putClientProperty("JButton.buttonType", "toolBarButton")
            addActionListener { showGrid() }
        }
        val open = JButton(state.strings.openSourceLabel).apply {
            putClientProperty("JButton.buttonType", "toolBarButton")
            toolTipText = state.strings.openTooltip
            addActionListener { state.onImageOpened(result) }
        }

        val footer = JPanel(BorderLayout(8, 0)).apply {
            border = EmptyBorder(6, 8, 8, 8)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(caption, BorderLayout.NORTH)
                    add(credit, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER
            )
            add(
                JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    add(back)
                    add(Box.createHorizontalStrut(4))
                    add(open)
                },
                BorderLayout.LINE_END
            )
        }

        body.removeAll()
        body.add(picture, BorderLayout.CENTER)
        body.add(footer, BorderLayout.SOUTH)
        body.revalidate()
        body.repaint()
    }

    /** Returns from the enlarged view to the grid, leaving the tiles and their images intact. */
    private fun showGrid() {
        preview = null
        body.removeAll()
        body.add(scroll, BorderLayout.CENTER)
        body.revalidate()
        body.repaint()
        applyColumnCount()
    }

    private fun fullCreditFor(result: ImageResult): String =
        listOfNotNull(result.attribution, result.license).joinToString(" · ").ifBlank { "" }

    /**
     * A label that shortens its own text to whatever width it is given.
     *
     * A plain `JLabel` clips, which cuts a word in half and leaves no sign that anything is
     * missing. Eliding happens during layout because the tile's width is not known until then,
     * and re-runs on resize.
     */
    private class ElidingLabel(private val fullText: String) : JLabel(fullText) {

        /** Guards the setText inside doLayout, which would otherwise re-enter through layout. */
        private var eliding = false

        init {
            // Keeps the grid's cells sized by the picture rather than by the longest caption.
            preferredSize = Dimension(0, preferredSize.height)
        }

        override fun doLayout() {
            super.doLayout()
            if (eliding) return
            eliding = true
            try {
                setText(elide(fullText, width - insets.left - insets.right))
            } finally {
                eliding = false
            }
        }

        private fun elide(text: String, available: Int): String {
            if (text.isEmpty() || available <= 0) return text
            val metrics = getFontMetrics(font)
            if (metrics.stringWidth(text) <= available) return text

            val ellipsis = "…"
            val room = available - metrics.stringWidth(ellipsis)
            if (room <= 0) return ellipsis

            var end = text.length
            while (end > 0 && metrics.stringWidth(text.substring(0, end)) > room) end--
            return text.substring(0, end).trimEnd() + ellipsis
        }
    }

    /**
     * Draws an image scaled to fit, keeping its proportions.
     *
     * Scaling happens while painting rather than by producing a resized copy. `getScaledInstance`
     * with `SCALE_SMOOTH` is the slow path in AWT, and the enlarged view rescaled on every resize
     * event — so dragging the popup's edge ran it continuously on the event thread. Letting
     * `drawImage` do it with a bilinear hint costs nothing per resize and looks the same.
     *
     * Never scaled above 1:1: a thumbnail stretched past its own resolution looks worse than a
     * smaller sharp one.
     */
    private class ScaledImage : JComponent() {

        var image: Image? = null
            set(value) {
                field = value
                repaint()
            }

        override fun paintComponent(g: Graphics) {
            val source = image ?: return
            val sourceWidth = source.getWidth(null)
            val sourceHeight = source.getHeight(null)
            if (sourceWidth <= 0 || sourceHeight <= 0) return

            // Painted on a copy so the hints do not leak into whatever Swing draws next.
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
                )
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

                val scale = minOf(
                    width.toDouble() / sourceWidth,
                    height.toDouble() / sourceHeight
                ).coerceAtMost(1.0)
                val drawWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
                val drawHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)

                g2.drawImage(source, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, null)
            } finally {
                g2.dispose()
            }
        }
    }

    private fun applyPinStyle(pinned: Boolean) = popup.applyPinBorder(pinned)

    private fun applyPosition(config: ImageSearchConfig) {
        if (config.positionNearMouse) popup.positionNearMouse() else popup.positionBesideOwner()
    }

    /**
     * Applies the configured transparency.
     *
     * Guarded because a window that is not translucency-capable throws rather than declining, and
     * an opacity of exactly 1 is rejected on some platforms after the window is displayable.
     */
    private fun applyTransparency(config: ImageSearchConfig) {
        val target = ((100 - config.transparencyPercentage).coerceIn(1, 100)) / 100f
        runCatching { opacity = target }
    }

    private fun saveGeometry() {
        currentState?.onSavePosition?.invoke(Position(location.x, location.y))
        currentState?.onSaveSize?.invoke(Size(size.width, size.height))
    }

    /**
     * Releases the thumbnail pool along with the window.
     *
     * Overrides the real `dispose` rather than adding a variant beside it: the previous
     * `dispose(Boolean)` was never called from anywhere, so the pool it was meant to shut down
     * never was.
     */
    override fun dispose() {
        thumbnails.shutdown()
        popup.uninstallTheme()
        super.dispose()
    }
}
