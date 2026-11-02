package com.github.ahatem.qtranslate.ui.swing.settings

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.settings.panels.SettingsPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The settings sidebar's section tree and the pages it opens.
 *
 * Owns selection — tree clicks, [selectPage] requests from outside or from search — lazy page
 * construction (a page is built the first time it is shown or searched, and the same cache
 * serves both so searching never builds a page twice), and the flash that marks where a search
 * hit landed. The dialog stays out of all of it: it hands over a factory for pages and is told
 * when one becomes visible.
 *
 * @param orientation supplies the dialog's layout direction, read when a page is built — pages
 * built after the dialog was flipped to right-to-left must not be laid out left-to-right.
 */
internal class SettingsNavigationController(
    private val navigation: SettingsNavigation,
    private val contentArea: JPanel,
    private val panelFactory: (String) -> JPanel,
    private val orientation: () -> ComponentOrientation,
    private val onPageShown: (String) -> Unit,
    private val renderPanel: (JPanel) -> Unit,
) {
    /** The dashed marker's overlay; the dialog installs it as its glass pane. */
    val markerOverlay = MarkerOverlay()

    /**
     * Theme-aware sidebar icons: 14 × 14 [FlatSVGIcon] with a [FlatSVGIcon.ColorFilter]
     * that remaps every SVG color to `Label.disabledForeground` at paint time, so icons
     * always match the active FlatLaf theme without any manual update.
     *
     * Declared before [tree]: building the tree already asks the renderer for a row icon,
     * and that renderer reads this map.
     */
    private val sidebarIcons: Map<String, Icon> by lazy {
        navigation.iconPaths.mapNotNull { (name, path) ->
            runCatching {
                val icon = IconSet.load(path, 14, 14)
                icon.colorFilter =
                    FlatSVGIcon.ColorFilter { UIManager.getColor("Label.disabledForeground") ?: Color.GRAY }
                name to (icon as Icon)
            }.getOrNull()
        }.toMap()
    }

    /** The sidebar's section tree; the search controller hosts it in its card space. */
    val tree: JTree = buildTree()

    private val panelCache = mutableMapOf<String, JPanel>()
    private var currentPanelName: String? = null

    /** Row under the pointer, or -1. Drives the sidebar's hover shape. */
    private var hoveredRow: Int = -1

    /**
     * Ends the marker currently showing, if any.
     *
     * Held as the whole cleanup rather than just the timer, so a second result clears the first
     * on the way past instead of leaving it on screen.
     */
    private var activeFlash: (() -> Unit)? = null

    /** The panel currently displayed, or null before the first page is shown. */
    val currentPanel: JPanel? get() = currentPanelName?.let { panelCache[it] }

    /**
     * Reads one page's searchable settings, building the page on first use.
     *
     * The panel lands in the same cache a later navigation would use, so a page found by search
     * is never rebuilt when it is opened by hand.
     */
    fun searchEntries(pageLabel: String): List<SettingsPanel.SettingEntry> {
        val panel = panelCache.getOrPut(pageLabel) { createPanel(pageLabel) }
        return (panel as? SettingsPanel)?.searchEntries.orEmpty()
    }

    /** Selects nothing but the first page row, as the sidebar does at open time. */
    fun selectFirstPage() {
        tree.setSelectionRow(0)
    }

    /** Moves the sidebar selection to [pageLabel], which in turn shows its panel. */
    fun selectPage(pageLabel: String) {
        for (row in 0 until tree.rowCount) {
            val node = tree.getPathForRow(row).lastPathComponent as? DefaultMutableTreeNode
            if (node?.userObject == pageLabel) {
                tree.setSelectionRow(row)
                tree.scrollRowToVisible(row)
                return
            }
        }
    }

    fun showPanel(name: String) {
        currentPanelName = name
        onPageShown(name)

        val panel = panelCache.getOrPut(name) { createPanel(name) }

        contentArea.components.filterIsInstance<JScrollPane>().forEach { contentArea.remove(it) }
        contentArea.add(JScrollPane(panel).apply {
            clearBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = 16
        }, BorderLayout.CENTER)

        contentArea.revalidate()
        contentArea.repaint()

        SwingUtilities.updateComponentTreeUI(panel)

        renderPanel(panel)
    }

    /**
     * Shows the page a hit belongs to, reveals the hit's row, and flashes it.
     *
     * The panel may have been built for the search index and never shown, in which case it has
     * no layout yet and every component in it measures zero; the deferred validate is what
     * gives the anchor a real size to scroll to and mark around.
     */
    fun openHit(hit: SearchHit) {
        selectPage(hit.pageLabel)
        SwingUtilities.invokeLater {
            // The panel may have been built for the search index and never shown, in which case
            // it has no layout yet and every component in it measures zero. Scrolling to a
            // zero-sized component lands at the top of the page and the marker is drawn around
            // nothing, which is why results on unvisited pages appeared to do nothing at all.
            contentArea.validate()

            val anchor = hit.entry.anchor
            anchor.scrollRectToVisible(Rectangle(0, 0, anchor.width, anchor.height))

            // Once more, after the scroll has settled: the marker is positioned in window
            // coordinates, so it has to be placed where the row ended up rather than where it
            // was before the viewport moved.
            SwingUtilities.invokeLater { flash(anchor) }
        }
    }

    /** Re-renders the current panel — the dialog calls this on every store emission. */
    fun renderCurrentPanel() {
        currentPanel?.let(renderPanel)
    }

    /**
     * Briefly tints the found setting.
     *
     * A page can hold thirty rows, and landing on the right one without a marker leaves the
     * reader to find it again by eye, which is the work the search was meant to save.
     */
    private fun flash(component: JComponent) {
        activeFlash?.invoke()
        if (component.width == 0 || component.height == 0) return

        // Drawn on the glass pane rather than on the row itself. A component clips its own
        // painting to its bounds, so a marker drawn around a row was cut off exactly where it
        // needed to be visible, leaving only the faint fill inside. The glass pane spans the
        // window, so the outline can sit outside the row with room to breathe, and nothing in
        // the page moves to make space for it.
        val bounds = SwingUtilities.convertRectangle(
            component.parent,
            component.bounds,
            markerOverlay
        )
        markerOverlay.markAt(bounds)

        val timer = Timer(FLASH_TICK_MILLIS, null)
        val finish = {
            timer.stop()
            markerOverlay.clear()
            activeFlash = null
        }

        val started = System.currentTimeMillis()
        timer.addActionListener {
            val elapsed = (System.currentTimeMillis() - started).toFloat()
            if (elapsed >= FLASH_MILLIS) {
                finish()
            } else {
                // Held at full strength for the first part, then faded. Fading from the first
                // frame makes it read as a flicker; holding first makes it read as a marker.
                markerOverlay.strength = flashStrength(elapsed)
                markerOverlay.repaint()
            }
        }
        activeFlash = finish
        timer.start()
    }

    /**
     * Builds a page and gives it the dialog's layout direction.
     *
     * Pages are built the first time they are opened, which is after the caller has applied an
     * orientation to the dialog — and `applyComponentOrientation` only reaches the children that
     * exist when it runs. So in a right-to-left interface every page except the one showing at
     * open time was laid out left-to-right, which is why some looked mirrored and others did not.
     */
    private fun createPanel(name: String): JPanel =
        panelFactory(name).apply { applyComponentOrientation(orientation()) }

    private fun buildTree(): JTree {
        val root = DefaultMutableTreeNode("root")
        navigation.tree.forEach { nav ->
            val node = DefaultMutableTreeNode(nav.label)
            if (nav is Nav.Group) nav.children.forEach { node.add(DefaultMutableTreeNode(it.label)) }
            root.add(node)
        }

        return object : JTree(DefaultTreeModel(root)) {
            /**
             * Draws the rollover behind the row, across the tree's whole width.
             *
             * It has to happen here rather than in the cell renderer: Swing clips a component's
             * painting to its own bounds, and a renderer is only as wide as its icon and text, so
             * a pill drawn there came out short for "General" and long for "Services & Presets"
             * and started at the indent on grouped rows. The tree owns the full width, so the
             * hover and the selection can finally land on the same pixels.
             *
             * The tree is left non-opaque and its background painted here by hand. Painting the
             * hover before `super` and leaving the tree opaque does not work: `paintComponent`
             * delegates to `ui.update`, which fills the background first and erased the shape on
             * every repaint. Painting after `super` would cover the label instead. Filling the
             * background here puts the hover between the two, which is the only place it belongs.
             */
            override fun paintComponent(g: java.awt.Graphics) {
                g.color = background
                g.fillRect(0, 0, width, height)

                val row = hoveredRow
                if (row >= 0 && !isRowSelected(row)) {
                    getRowBounds(row)?.let { bounds ->
                        val g2 = g.create() as java.awt.Graphics2D
                        try {
                            g2.setRenderingHint(
                                java.awt.RenderingHints.KEY_ANTIALIASING,
                                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                            )
                            g2.color = UIManager.getColor("Tree.selectionInactiveBackground")
                                ?: UIManager.getColor("Component.borderColor")
                            // The same insets and radius FlatLaf uses for the selection.
                            val side = UIScale.scale(5)
                            val top = UIScale.scale(1)
                            val arc = UIScale.scale(8)
                            g2.fillRoundRect(
                                side, bounds.y + top,
                                (width - side * 2).coerceAtLeast(0), bounds.height - top * 2,
                                arc, arc
                            )
                        } finally {
                            g2.dispose()
                        }
                    }
                }
                super.paintComponent(g)
            }
        }.apply {
            // Painted by hand above, so the look and feel must not fill it again.
            isOpaque = false

            // The tree adds no indent of its own. It had two sources — the look and feel's child
            // indents plus a top-up in the renderer that subtracted the *unscaled* default from a
            // scaled one — so the real indent was neither number and could not be reasoned about.
            // Zero here leaves NEST_INDENT as the only thing that decides it.
            (ui as? javax.swing.plaf.basic.BasicTreeUI)?.let {
                it.leftChildIndent = 0
                it.rightChildIndent = 0
            }
            isRootVisible = false
            showsRootHandles = false
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            // Groups exist to label their children, not to hide them. Expanded and left that way;
            // collapsing is still possible by clicking the handle.
            for (row in rowCount - 1 downTo 0) expandRow(row)

            // A tree has no rollover state of its own. Without one this was the only
            // navigation in the application that stayed inert under the pointer, which is most
            // of why it read as a list rather than as something to click.
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: java.awt.event.MouseEvent) {
                    val row = getRowForLocation(e.x, e.y)
                    if (row != hoveredRow) {
                        hoveredRow = row
                        repaint()
                    }
                }
            })
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    hoveredRow = -1
                    repaint()
                }
            })

            putClientProperty(
                "FlatLaf.style",
                // Compact rows, and a selection shape the hover state matches exactly.
                "rowHeight: 26; selectionArc: 8; selectionInsets: 1,5,1,5; " +
                        $$"selectionBackground: $Table.selectionBackground"
            )

            cellRenderer = object : DefaultTreeCellRenderer() {
                init {
                    leafIcon = null; closedIcon = null; openIcon = null
                }

                // Derived once from a stable base. A cell renderer is a single component reused for
                // every row, so deriving from its *current* font compounds: each heading shrank the
                // shared font by a pixel and every row drawn afterwards inherited the smaller one,
                // until the labels vanished entirely.
                private val itemFont: Font =
                    (UIManager.getFont("Tree.font") ?: UIManager.getFont("Label.font"))
                        .deriveFont(Font.PLAIN)
                private val groupFont: Font = itemFont.deriveFont(Font.BOLD, itemFont.size - 1f)

                override fun getTreeCellRendererComponent(
                    tree: JTree, value: Any, sel: Boolean,
                    expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
                ): Component {
                    super.getTreeCellRendererComponent(
                        tree, value, sel, expanded, leaf, row, hasFocus
                    )

                    val name = (value as? DefaultMutableTreeNode)?.userObject as? String ?: ""
                    text = name
                    icon = sidebarIcons[name]
                    iconTextGap = 8

                    // A group is a heading for the rows beneath it, so it is styled as one
                    // rather than competing with the pages it labels.
                    val isGroup = navigation.isGroup(name)

                    // Headings and ungrouped pages sit at the base; a page inside a group sits
                    // further in, so the nesting is visible at a glance.
                    //
                    // An earlier version pushed ungrouped pages *out* to meet the grouped ones so
                    // every page shared one left edge. That reads as a flat list with two stray
                    // headings in it — the grouping is there in the markup and invisible on
                    // screen. The hierarchy is the point, so it is the thing to show.
                    //
                    // The tree indents its own children, but by an amount the look and feel
                    // chooses, which can be too small to read as nesting. Whatever it gives is
                    // topped up to a minimum rather than replaced, so this neither fights the
                    // look and feel nor depends on it.
                    val depth = (value as? DefaultMutableTreeNode)?.level ?: 1
                    val nesting = if (depth > 1) UIScale.scale(NEST_INDENT) else 0

                    // EmptyBorder takes absolute sides and knows nothing about direction, so the
                    // leading edge is picked here. Written always on the left, the indent moved to
                    // the far end of the row in a right-to-left interface.
                    val base = UIScale.scale(ROW_INSET)
                    val leading = base + nesting
                    border = if (tree.componentOrientation.isLeftToRight) {
                        BorderFactory.createEmptyBorder(0, leading, 0, base)
                    } else {
                        BorderFactory.createEmptyBorder(0, base, 0, leading)
                    }
                    // A heading labels the rows beneath it, so it is quieter and smaller than
                    // they are. Set at the same size and weight it competed with them, and the
                    // sidebar read as one long list with two odd entries in it.
                    font = if (isGroup) groupFont else itemFont
                    if (!sel) {
                        foreground = UIManager.getColor(
                            if (isGroup) "Label.disabledForeground" else "Label.foreground"
                        )
                    }

                    // Headings carry no icon. Giving them one made them look selectable, and the
                    // blank space where an icon would be is what tells the eye they are not.
                    if (isGroup) icon = null

                    return this
                }
            }

            addTreeSelectionListener { e ->
                val node = e.path.lastPathComponent as? DefaultMutableTreeNode
                    ?: return@addTreeSelectionListener
                val name = node.userObject as? String ?: return@addTreeSelectionListener

                // Groups have no page. Selecting one opens its first child, which is more useful
                // than doing nothing and avoids a selected row with a blank content area.
                val firstChild = navigation.firstChildOf(name)
                if (firstChild != null) {
                    selectPage(firstChild.label)
                    return@addTreeSelectionListener
                }
                showPanel(name)
            }
        }
    }

    /**
     * A dashed marker drawn over the window, around whichever row the search just found.
     *
     * Lives on the glass pane rather than on the row, because a component clips its own painting
     * to its bounds: a marker drawn around a row was cut off exactly where it needed to be seen.
     * From up here it can sit outside the row with padding, and no layout changes to make room.
     *
     * Colours are read at paint time, so a theme change while it is up cannot leave a stale one.
     */
    internal class MarkerOverlay : JComponent() {
        /** 1 while held, falling to 0 as it fades out. */
        var strength: Float = 1f

        private var target: Rectangle? = null

        init {
            isOpaque = false
            isVisible = false
        }

        fun markAt(bounds: Rectangle) {
            target = bounds
            strength = 1f
            isVisible = true
            repaint()
        }

        fun clear() {
            target = null
            isVisible = false
            repaint()
        }

        /**
         * Never claims the pointer.
         *
         * A visible glass pane swallows every click underneath it by default, which would make
         * the settings unusable for as long as the marker is up.
         */
        override fun contains(x: Int, y: Int) = false

        override fun paintComponent(g: Graphics) {
            val rect = target ?: return
            if (strength <= 0f) return

            val accent = UIManager.getColor("Component.accentColor")
                ?: UIManager.getColor("Table.selectionBackground")
                ?: Color(86, 156, 214)

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val padX = UIScale.scale(7)
                val padY = UIScale.scale(5)
                val arc = UIScale.scale(9)
                val left = rect.x - padX
                val top = rect.y - padY
                val width = rect.width + padX * 2
                val height = rect.height + padY * 2

                g2.color = Color(accent.red, accent.green, accent.blue, (FILL_ALPHA * strength).toInt())
                g2.fillRoundRect(left, top, width, height, arc, arc)

                // Dashed, in the idiom the plugin drop zone already uses, and thick enough to
                // carry the emphasis on its own — the fill is only there to seat it.
                g2.color = Color(accent.red, accent.green, accent.blue, (OUTLINE_ALPHA * strength).toInt())
                g2.stroke = BasicStroke(
                    UIScale.scale(2f),
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_ROUND,
                    1f,
                    floatArrayOf(UIScale.scale(6f), UIScale.scale(4f)),
                    0f
                )
                g2.drawRoundRect(left, top, width - 1, height - 1, arc, arc)
            } finally {
                g2.dispose()
            }
        }

        private companion object {
            /** Faint: the outline does the work, and the row's own text has to stay readable. */
            const val FILL_ALPHA = 38f
            const val OUTLINE_ALPHA = 255f
        }
    }

    internal companion object {
        const val FLASH_MILLIS = 2200f
        const val FLASH_TICK_MILLIS = 30

        /** How much of the flash is held at full strength before it starts fading. */
        const val FLASH_HOLD_FRACTION = 0.6f

        /** Breathing room at the leading and trailing edge of every sidebar row. */
        const val ROW_INSET = 8

        /**
         * Smallest gap between an ungrouped page and one nested under a heading.
         *
         * A floor, not a fixed value: the tree indents its own children first, and this only
         * makes up the difference when that comes out too small to read as nesting.
         */
        const val NEST_INDENT = 12

        /**
         * The marker's strength at [elapsedMillis] into the flash.
         *
         * Held at full strength for the first part, then faded. Fading from the first frame makes
         * it read as a flicker; holding first makes it read as a marker.
         */
        fun flashStrength(elapsedMillis: Float): Float {
            val fadeFrom = FLASH_MILLIS * FLASH_HOLD_FRACTION
            return if (elapsedMillis <= fadeFrom) 1f
            else 1f - (elapsedMillis - fadeFrom) / (FLASH_MILLIS - fadeFrom)
        }
    }
}
