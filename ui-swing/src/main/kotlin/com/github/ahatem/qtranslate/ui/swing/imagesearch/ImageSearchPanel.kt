package com.github.ahatem.qtranslate.ui.swing.imagesearch

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.icons.FlatSearchIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.imagesearch.ImageResult
import com.github.ahatem.qtranslate.core.shared.arch.UiState
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.InlineLoadingBar
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Image
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

/** What [ImageSearchPanel] shows, and what it reports back. */
data class ImageSearchPanelState(
    val isLoading: Boolean,
    val results: List<ImageResult>,
    val searchedTerm: String,
    val hasFailed: Boolean,
    val strings: ImageSearchStrings,
    val onSearch: (term: String) -> Unit,
    val onImageOpened: (ImageResult) -> Unit
) : UiState

/**
 * The pictures for a term: a search field, and beneath it the results as a grid that can be opened
 * to one enlarged image.
 *
 * It is only content. It does not know whether it is docked in the main window or floating in a
 * popup, and owns nothing of a window: no title, no pin, no position. The hosts supply those, which
 * is what lets the dock and the popup show exactly the same thing.
 *
 * The content rhythm below -- outer inset, the gap from the search field to the results, the tile
 * grid, the gap from a picture to its caption and from there to its credit -- belongs entirely to
 * this panel. It is the same whichever host mounts it, so a host adds no padding of its own around
 * it; one that already insets its own chrome, as the lookup dock's tab content does for the
 * dictionary, mounts this panel directly rather than inside that inset.
 */
class ImageSearchPanel : JPanel(BorderLayout()), Renderable<ImageSearchPanelState> {

    private companion object {
        /**
         * The narrowest a tile may get before the picture in it stops being readable.
         *
         * Columns are derived from this rather than fixed, so a narrow host shows one usable
         * image instead of three unusable ones. A diagram is a smudge below roughly this width.
         */
        const val MIN_TILE_WIDTH = 180
        const val MAX_COLUMNS = 4
        const val TILE_HEIGHT = 124
        const val GRID_GAP = 8

        /** Left/right inset shared by the search row and the grid, so their edges line up. */
        const val CONTENT_INSET = 12

        /** Top inset for the search row, and bottom inset for the grid: the panel's own margin. */
        const val OUTER_INSET = 12

        /** Gap from the bottom of the search field to the first row of tiles. */
        const val SEARCH_TO_RESULTS_GAP = 8

        /** Gap from a picture to its caption. */
        const val IMAGE_TO_CAPTION_GAP = 4

        /** Gap from a tile's caption to its credit line. */
        const val CAPTION_TO_CREDIT_GAP = 2
    }

    // Read on each use rather than captured once: a colour held in a field keeps the value the
    // theme had when this panel was built, and hosts live for the whole session.
    private val borderColor: Color get() = UIManager.getColor("Component.borderColor") ?: Color.GRAY

    private val thumbnails = ThumbnailLoader()

    /**
     * A search field in the look and feel's own idiom rather than a bare text box: FlatLaf draws the
     * magnifier and the clear button itself, so they follow the theme and the scale factor.
     */
    private val searchField = JTextField().apply {
        putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, FlatSearchIcon())
        putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true)
        addActionListener { currentState?.onSearch?.invoke(text.trim()) }
    }

    private val hintLabel = JLabel("", SwingConstants.CENTER).apply {
        foreground = UIManager.getColor("Label.disabledForeground")
        border = EmptyBorder(UIScale.scale(24), UIScale.scale(CONTENT_INSET), UIScale.scale(24), UIScale.scale(CONTENT_INSET))
    }

    private val grid = JPanel(GridLayout(0, 1, UIScale.scale(GRID_GAP), UIScale.scale(GRID_GAP))).apply {
        // No top inset: the search row's own bottom margin is the whole search-to-results gap, so
        // the two are not added together into a double gap.
        border = EmptyBorder(0, UIScale.scale(CONTENT_INSET), UIScale.scale(OUTER_INSET), UIScale.scale(CONTENT_INSET))
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

    private var currentState: ImageSearchPanelState? = null

    /**
     * The results the grid was last built from.
     *
     * Rebuilding tiles on every render would restart every thumbnail fetch and lose the reader's
     * scroll position, and render runs on any state change, including ones this panel does not
     * care about.
     */
    private var renderedResults: List<ImageResult> = emptyList()

    /** The term the field was last set from, so a render never overwrites what is being typed. */
    private var appliedTerm = ""

    init {
        val searchRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(
                UIScale.scale(OUTER_INSET), UIScale.scale(CONTENT_INSET),
                UIScale.scale(SEARCH_TO_RESULTS_GAP), UIScale.scale(CONTENT_INSET)
            )
            add(searchField, BorderLayout.CENTER)
        }
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(searchRow, BorderLayout.CENTER)
                add(loadingBar, BorderLayout.SOUTH)
            },
            BorderLayout.NORTH
        )
        add(body.apply { add(scroll, BorderLayout.CENTER) }, BorderLayout.CENTER)

        installResponsiveColumns()
    }

    /** Whether an enlarged image is showing, which is where Escape should go back from. */
    val isShowingPreview: Boolean get() = preview != null

    /** Goes back from an enlarged image to the grid; returns false when there was nothing to go back from. */
    fun stepBack(): Boolean {
        if (preview == null) return false
        showGrid()
        return true
    }

    fun focusSearchField() {
        searchField.requestFocusInWindow()
    }

    /** The text field, for hosts that need to route focus to it. */
    val searchFieldComponent: JComponent get() = searchField

    /** The text currently in the search field. */
    val searchText: String get() = searchField.text

    /** Releases the thumbnail pool. Call when the host is discarded. */
    fun dispose() {
        thumbnails.shutdown()
    }

    /**
     * Re-applies the colours already painted into borders and labels, which a theme switch does
     * not revisit.
     */
    fun refreshTheme() {
        hintLabel.foreground = UIManager.getColor("Label.disabledForeground")
        // Tiles carry a border and a dimmed credit line, and are cheapest to simply rebuild.
        renderedResults = emptyList()
        currentState?.let { rebuildGridIfChanged(it) }
        revalidate()
        repaint()
    }

    override fun render(state: ImageSearchPanelState) {
        currentState = state
        loadingBar.isLoading = state.isLoading
        hintLabel.text = when {
            state.isLoading -> state.strings.loadingMessage
            state.hasFailed -> state.strings.errorMessage
            state.searchedTerm.isNotBlank() && state.results.isEmpty() -> state.strings.notFoundMessage
            else -> state.strings.hintMessage
        }
        rebuildGridIfChanged(state)

        // The field shows the word being searched when that changes from outside, such as a new
        // selection, and is otherwise left to whoever is typing in it.
        if (state.searchedTerm != appliedTerm) {
            appliedTerm = state.searchedTerm
            if (state.searchedTerm.isNotBlank() && searchField.text != state.searchedTerm) {
                searchField.text = state.searchedTerm
            }
        }
    }

    /**
     * Recomputes how many tiles fit across whenever the panel is resized.
     *
     * Driven by the viewport rather than the panel so the scrollbar's width is already accounted
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

    /** The number of tile columns currently laid out. */
    internal fun columnCountForTest(): Int = (grid.layout as GridLayout).columns

    internal fun tileCountForTest(): Int = grid.componentCount

    private fun rebuildGridIfChanged(state: ImageSearchPanelState) {
        if (state.results.isEmpty()) {
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
    private fun tileFor(result: ImageResult, state: ImageSearchPanelState): JComponent {
        val picture = ScaledImage().apply {
            preferredSize = Dimension(0, UIScale.scale(TILE_HEIGHT))
            border = BorderFactory.createLineBorder(borderColor, 1)
        }

        val caption = ElidingLabel(result.title.orEmpty()).apply {
            putClientProperty("FlatLaf.styleClass", "small")
            toolTipText = result.title
        }

        // The licence alone, dimmed. The author's name is long and varies wildly in length, so it
        // lives in the tooltip and on the page a click opens, which is where a licence expects to
        // be honoured anyway.
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

        return JPanel(BorderLayout(0, UIScale.scale(IMAGE_TO_CAPTION_GAP))).apply {
            add(picture, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout(0, UIScale.scale(CAPTION_TO_CREDIT_GAP))).apply {
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
                    // Enlarged here rather than in a browser: someone who wants to know what a word
                    // looks like is answered by the picture, and the source page is one click on.
                    showPreview(result)
                }
            })
        }
    }

    /** Fills the panel with one image. */
    private fun showPreview(result: ImageResult) {
        val state = currentState ?: return
        preview = result

        // Sizes itself to the panel as it changes, without rescaling work per resize event.
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

        val footer = JPanel(BorderLayout(UIScale.scale(CONTENT_INSET), 0)).apply {
            border = EmptyBorder(
                UIScale.scale(SEARCH_TO_RESULTS_GAP), UIScale.scale(CONTENT_INSET),
                UIScale.scale(OUTER_INSET), UIScale.scale(CONTENT_INSET)
            )
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
                    add(Box.createHorizontalStrut(UIScale.scale(CAPTION_TO_CREDIT_GAP * 2)))
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
     * event ran it continuously on the event thread. Never scaled above 1:1: a thumbnail stretched
     * past its own resolution looks worse than a smaller sharp one.
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
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

                val scale = minOf(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight).coerceAtMost(1.0)
                val drawWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
                val drawHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)

                g2.drawImage(source, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, null)
            } finally {
                g2.dispose()
            }
        }
    }
}
