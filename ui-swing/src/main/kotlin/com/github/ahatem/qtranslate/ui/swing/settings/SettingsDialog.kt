package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.icons.FlatSearchIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsEvent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.settings.panels.*
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*
import javax.swing.border.MatteBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class SettingsDialog(
    owner: JFrame,
    private val settingsStore: SettingsStore,
    private val pluginManager: PluginManager,
    private val iconManager: IconManager,
    private val themeManager: ThemeManager,
    private val localizationManager: LocalizationManager,
    /**
     * Snapshot of languages currently supported by the active translator.
     * Evaluated lazily so [GeneralPanel] always gets the latest list when
     * it renders — not whatever was available when the dialog was constructed.
     */
    private val availableLanguages: () -> List<com.github.ahatem.qtranslate.api.language.LanguageCode> = { emptyList() },
    /** Invoked just before the hotkey recorder opens; should disable global hotkeys. */
    private val pauseGlobalHotkeys:  (() -> Unit)? = null,
    /** Invoked after the recorder closes; should restore the global hotkey state. */
    private val resumeGlobalHotkeys: (() -> Unit)? = null,
    /**
     * Translates one string, for the language editor's suggestion action. Absent when nothing can
     * translate, which hides the action rather than offering one that fails.
     */
    private val translateString: (suspend (String, LanguageCode) -> Result<String>)? = null,
) : JDialog(owner, "Settings", true) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Held as a field so it can be removed again — a lambda passed inline never can be. */
    private val themeListener = java.beans.PropertyChangeListener { event ->
        if (event.propertyName == "lookAndFeel") SwingUtilities.invokeLater { updateBorders() }
    }

    /** A sidebar entry. Groups own children and have no page of their own. */
    private sealed interface Nav {
        val label: String

        data class Page(override val label: String, val iconPath: String) : Nav
        data class Group(override val label: String, val children: List<Page>) : Nav
    }

    private fun label(key: String) = localizationManager.getString("settings_dialog_sidebar.$key")

    /**
     * The sidebar, in order.
     *
     * Grouped only where sections genuinely cluster. Everything under one parent would be
     * IntelliJ cosplay at this size, and a flat list left the three translation-related pages
     * separated by Plugins and Hotkeys.
     */
    private val navTree: List<Nav> = listOf(
        Nav.Page(label("general"), "icons/lucide/sliders-horizontal.svg"),
        Nav.Page(label("appearance"), "icons/lucide/palette.svg"),
        Nav.Group(
            label("group_translation"), listOf(
                Nav.Page(label("services"), "icons/lucide/zap.svg"),
                Nav.Page(label("behavior"), "icons/lucide/languages.svg"),
                Nav.Page(label("languages"), "icons/lucide/globe.svg"),
            )
        ),
        Nav.Group(
            label("group_interface"), listOf(
                Nav.Page(label("layout"), "icons/lucide/layout-dashboard.svg"),
                Nav.Page(label("popups"), "icons/lucide/message-square.svg"),
            )
        ),
        Nav.Page(label("hotkeys"), "icons/lucide/keyboard.svg"),
        Nav.Page(label("plugins"), "icons/lucide/package.svg"),
    )

    /** Every selectable page, flattened, in sidebar order. */
    private val pages: List<Nav.Page> = navTree.flatMap {
        when (it) {
            is Nav.Page -> listOf(it)
            is Nav.Group -> it.children
        }
    }

    /** SVG resource paths keyed by localized nav label. Groups carry no icon of their own. */
    private val sidebarIconPaths: Map<String, String> =
        pages.associate { it.label to it.iconPath }

    /**
     * Theme-aware sidebar icons: 14 × 14 [FlatSVGIcon] with a [FlatSVGIcon.ColorFilter]
     * that remaps every SVG color to `Label.disabledForeground` at paint time, so icons
     * always match the active FlatLaf theme without any manual update.
     */
    private val sidebarIcons: Map<String, Icon> by lazy {
        sidebarIconPaths.mapNotNull { (name, path) ->
            runCatching {
                val icon = FlatSVGIcon(path, 14, 14, javaClass.classLoader)
                icon.colorFilter =
                    FlatSVGIcon.ColorFilter { UIManager.getColor("Label.disabledForeground") ?: Color.GRAY }
                name to (icon as Icon)
            }.getOrNull()
        }.toMap()
    }

    // ── Widgets ───────────────────────────────────────────────────────────────
    private val tree: JTree

    private val searchField = JTextField()

    /** Swaps between the section tree and the search results in the same space. */
    private val navCards = JPanel(CardLayout())

    private val resultsList = JList<SearchHit>().apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ListCellRenderer<SearchHit> { list, hit, _, selected, _ ->
            JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(4, 10, 4, 10)
                isOpaque = true
                background = if (selected) {
                    UIManager.getColor("List.selectionBackground")
                } else {
                    list.background
                }
                add(JLabel(hit.entry.label).apply {
                    foreground = if (selected) {
                        UIManager.getColor("List.selectionForeground")
                    } else {
                        UIManager.getColor("Label.foreground")
                    }
                }, BorderLayout.NORTH)
                // The path is what tells two identically-labelled rows apart, so it is always
                // shown rather than only on ambiguity.
                add(JLabel("${hit.pageLabel} $PATH_SEPARATOR ${hit.entry.section}").apply {
                    font = font.deriveFont(font.size - 2f)
                    foreground = if (selected) {
                        UIManager.getColor("List.selectionForeground")
                    } else {
                        UIManager.getColor("Label.disabledForeground")
                    }
                }, BorderLayout.SOUTH)
            }
        }
        addListSelectionListener { e ->
            if (!e.valueIsAdjusting) (selectedValue as? SearchHit)?.let { openHit(it) }
        }

        // Selection listeners only fire on a change, so clicking the row you are already on did
        // nothing — and that is exactly when you want the marker shown again, having lost track of
        // it. Only the already-selected row is handled here; anything else is a real selection
        // change and the listener above has it.
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                val index = locationToIndex(e.point)
                if (index < 0 || index != selectedIndex) return
                // locationToIndex answers with the nearest row even for a click in the empty space
                // below the last one, so the click has to be inside the row it named.
                val bounds = getCellBounds(index, index) ?: return
                if (bounds.contains(e.point)) openHit(model.getElementAt(index))
            }
        })
    }

    /** Built on the first search and reused; see [searchIndex]. */
    private var cachedIndex: List<SearchHit>? = null

    /**
     * Ends the marker currently showing, if any.
     *
     * Held as the whole cleanup rather than just the timer, so a second result clears the first
     * on the way past instead of leaving it on screen.
     */
    private var activeFlash: (() -> Unit)? = null

    private val markerOverlay = MarkerOverlay()

    private val noResultsLabel = JLabel().apply {
        foreground = UIManager.getColor("Label.disabledForeground")
        verticalAlignment = SwingConstants.TOP
    }

    private val contentArea = JPanel(BorderLayout())

    private val panelTitle = JLabel(pages.firstOrNull()?.label ?: "").apply {
        font = font.deriveFont(Font.BOLD, font.size + 3f)
    }

    private val panelCache = mutableMapOf<String, JPanel>()
    private var currentPanelName: String? = null

    private lateinit var okButton: JButton
    private lateinit var applyButton: JButton

    // ── Panels that own theme-sensitive borders (refreshed in updateBorders) ──
    private var sidebarPanel: JPanel = JPanel()
    private var headerStrip: JPanel = JPanel()
    private var buttonBarPanel: JPanel = JPanel()

    // ─────────────────────────────────────────────────────────────────────────

    init {
        com.github.ahatem.qtranslate.ui.swing.shared.util.AppIcons.applyTo(this)
        title = localizationManager.getString("settings_dialog.title")
        layout = BorderLayout()

        tree = buildTree()

        sidebarPanel = buildSidebar()

        // ── Header strip ─────────────────────────────────────────────────────
        headerStrip = JPanel(BorderLayout(12, 0)).apply {
            add(panelTitle, BorderLayout.LINE_START)
        }
        contentArea.add(headerStrip, BorderLayout.NORTH)

        val mainPanel = JPanel(BorderLayout()).apply {
            add(sidebarPanel, BorderLayout.LINE_START)
            add(contentArea, BorderLayout.CENTER)
        }

        buttonBarPanel = buildButtonBar()

        add(mainPanel, BorderLayout.CENTER)
        add(buttonBarPanel, BorderLayout.SOUTH)

        glassPane = markerOverlay

        // Apply borders based on current theme, then keep them fresh on theme changes
        updateBorders()
        UIManager.addPropertyChangeListener(themeListener)

        rootPane.registerKeyboardAction(
            { cancelAndClose() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = cancelAndClose()

            /**
             * Detaches from [UIManager] on the way out.
             *
             * A fresh dialog is built every time settings are opened, and `UIManager` holds its
             * listeners for the life of the process. Without this, each open pins an entire
             * dialog — every panel and everything they reference — in memory for good, and every
             * later theme change runs the handlers of all the dead ones.
             */
            override fun windowClosed(e: WindowEvent) {
                UIManager.removePropertyChangeListener(themeListener)
            }
        })

        observeState()

        minimumSize = Dimension(UIScale.scale(860), UIScale.scale(580))
        preferredSize = Dimension(UIScale.scale(1020), UIScale.scale(700))
        pack()
        setLocationRelativeTo(owner)

        tree.setSelectionRow(0)
    }

    /**
     * Recomputes anything that depends on layout direction, after it is known.
     *
     * The caller applies an orientation once the dialog is built, so everything decided during
     * construction was decided left-to-right. The sidebar's divider is the visible case: it is a
     * `MatteBorder` on an absolute edge, chosen from the orientation, and without this it stayed
     * on the edge it was given at construction and disappeared against the window frame.
     */
    override fun applyComponentOrientation(orientation: ComponentOrientation) {
        super.applyComponentOrientation(orientation)
        updateBorders()
        // Pages already built were reached by super above; ones built later read the dialog's
        // orientation in createPanel.
        revalidate()
        repaint()
    }

    // ── Theme-aware borders ───────────────────────────────────────────────────

    private fun updateBorders() {
        val bc = UIManager.getColor("Component.borderColor") ?: Color.GRAY

        // The divider goes on whichever edge faces the content, which swaps with the layout
        // direction: the sidebar sits on the right in a right-to-left interface, so a border
        // fixed to its right edge ends up on the outside of the window against nothing.
        // MatteBorder takes absolute sides and knows nothing about orientation, so the side is
        // chosen here.
        val leftToRight = componentOrientation.isLeftToRight
        sidebarPanel.border = MatteBorder(
            0,
            if (leftToRight) 0 else 1,
            0,
            if (leftToRight) 1 else 0,
            bc
        )

        // Divides the search box from the sections it searches, matching the rule under the
        // header strip on the other side of the sidebar so the two line up as one row of chrome.
        searchField.border = BorderFactory.createCompoundBorder(
            MatteBorder(0, 0, 1, 0, bc),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)
        )

        headerStrip.border = BorderFactory.createCompoundBorder(
            MatteBorder(0, 0, 1, 0, bc),
            BorderFactory.createEmptyBorder(12, 16, 12, 16)
        )

        buttonBarPanel.border = BorderFactory.createCompoundBorder(
            MatteBorder(1, 0, 0, 0, bc),
            BorderFactory.createEmptyBorder(0, 0, 0, 0)
        )

        revalidate()
        repaint()
    }

    // ── Sidebar ───────────────────────────────────────────────────────────────

    private fun buildTree(): JTree {
        val root = DefaultMutableTreeNode("root")
        navTree.forEach { nav ->
            val node = DefaultMutableTreeNode(nav.label)
            if (nav is Nav.Group) nav.children.forEach { node.add(DefaultMutableTreeNode(it.label)) }
            root.add(node)
        }

        return JTree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            showsRootHandles = false
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            // Groups exist to label their children, not to hide them. Expanded and left that way;
            // collapsing is still possible by clicking the handle.
            for (row in rowCount - 1 downTo 0) expandRow(row)

            putClientProperty(
                "FlatLaf.style",
                // Compact rows: 32px height, minimal selection arc, tight insets
                "rowHeight: 32; selectionArc: 6; selectionInsets: 1,6,1,6; " +
                        $$"selectionBackground: $Table.selectionBackground"
            )

            cellRenderer = object : DefaultTreeCellRenderer() {
                init {
                    leafIcon = null; closedIcon = null; openIcon = null
                }

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
                    val isGroup = navTree.any { it is Nav.Group && it.label == name }

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
                    val treeIndent = UIManager.getInt("Tree.leftChildIndent") +
                        UIManager.getInt("Tree.rightChildIndent")
                    val nesting = if (depth > 1) {
                        (UIScale.scale(NEST_INDENT) - treeIndent).coerceAtLeast(0)
                    } else {
                        0
                    }

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
                    font = font.deriveFont(if (isGroup) Font.BOLD else Font.PLAIN)
                    if (!sel) {
                        foreground = UIManager.getColor(
                            if (isGroup) "Label.disabledForeground" else "Label.foreground"
                        )
                    }
                    return this
                }
            }

            addTreeSelectionListener { e ->
                val node = e.path.lastPathComponent as? DefaultMutableTreeNode
                    ?: return@addTreeSelectionListener
                val name = node.userObject as? String ?: return@addTreeSelectionListener

                // Groups have no page. Selecting one opens its first child, which is more useful
                // than doing nothing and avoids a selected row with a blank content area.
                val group = navTree.firstOrNull { it is Nav.Group && it.label == name } as? Nav.Group
                if (group != null) {
                    selectPage(group.children.first().label)
                    return@addTreeSelectionListener
                }
                showPanel(name)
            }
        }
    }

    private fun buildSidebar(): JPanel {
        // Breathing room under the search box's rule. Without it the first row sits against the
        // line and reads as attached to it rather than as the first of a list.
        // An EmptyBorder rather than null: the look and feel reinstalls its own default over a
        // null border on every theme change, which is what clearBorder() exists to avoid.
        val listInset = BorderFactory.createEmptyBorder(UIScale.scale(6), 0, UIScale.scale(6), 0)
        tree.border = listInset
        resultsList.border = listInset

        val treeScroll = JScrollPane(tree).apply {
            clearBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        val resultsScroll = JScrollPane(resultsList).apply {
            clearBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        navCards.add(treeScroll, NAV_TREE)
        navCards.add(resultsScroll, NAV_RESULTS)
        navCards.add(
            JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(16, 12, 12, 12)
                add(noResultsLabel, BorderLayout.NORTH)
            },
            NAV_EMPTY
        )

        searchField.apply {
            putClientProperty(
                FlatClientProperties.PLACEHOLDER_TEXT,
                localizationManager.getString("settings_dialog.search_placeholder")
            )
            putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true)
            putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, FlatSearchIcon())
            // Border applied by updateBorders() so the rule follows the theme.

            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = onSearchChanged()
                override fun removeUpdate(e: DocumentEvent) = onSearchChanged()
                override fun changedUpdate(e: DocumentEvent) = onSearchChanged()
            })

            // Down from the field moves into the results without reaching for the mouse, which is
            // the whole point of typing rather than clicking.
            registerKeyboardAction(
                { if (resultsList.model.size > 0) { resultsList.requestFocusInWindow(); resultsList.selectedIndex = 0 } },
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
                JComponent.WHEN_FOCUSED
            )
        }

        return JPanel(BorderLayout()).apply {
            minimumSize = Dimension(UIScale.scale(200), 0)
            add(searchField, BorderLayout.NORTH)
            add(navCards, BorderLayout.CENTER)
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private fun onSearchChanged() {
        val query = searchField.text.trim()
        val cards = navCards.layout as CardLayout

        if (query.isEmpty()) {
            cards.show(navCards, NAV_TREE)
            return
        }

        val hits = searchIndex().filter { it.matches(query) }
        if (hits.isEmpty()) {
            // Naming the query back is the difference between "nothing matched what you typed"
            // and a sidebar that looks broken.
            noResultsLabel.text = "<html><body style='width:${UIScale.scale(140)}px'>" +
                localizationManager.getString("settings_dialog.search_no_results", query) +
                "</body></html>"
            cards.show(navCards, NAV_EMPTY)
            return
        }
        resultsList.setListData(hits.toTypedArray())
        cards.show(navCards, NAV_RESULTS)
    }

    /**
     * Every setting on every page, built once on the first search.
     *
     * Panels are created lazily as pages are opened, so until someone searches, most do not
     * exist to be asked. Building the rest here trades a pause on the first keystroke for an
     * index that cannot fall behind the panels it describes.
     *
     * Plugins is excluded deliberately: it is a manager for a list that changes at runtime, not a
     * page of settings, and it is the one panel whose construction does real work.
     */
    private fun searchIndex(): List<SearchHit> {
        cachedIndex?.let { return it }

        val index = pages
            .filter { it.label != label("plugins") }
            .flatMap { page ->
                val panel = panelCache.getOrPut(page.label) { createPanel(page.label) }
                (panel as? SettingsPanel)?.searchEntries.orEmpty()
                    .map { SearchHit(page.label, it) }
            }
        cachedIndex = index
        return index
    }

    private fun openHit(hit: SearchHit) {
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
                val fadeFrom = FLASH_MILLIS * FLASH_HOLD_FRACTION
                markerOverlay.strength = if (elapsed <= fadeFrom) 1f
                else 1f - (elapsed - fadeFrom) / (FLASH_MILLIS - fadeFrom)
                markerOverlay.repaint()
            }
        }
        activeFlash = finish
        timer.start()
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
    private class MarkerOverlay : JComponent() {
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

    private data class SearchHit(val pageLabel: String, val entry: SettingsPanel.SettingEntry) {
        fun matches(query: String): Boolean {
            val q = query.lowercase()
            return entry.label.lowercase().contains(q) ||
                entry.section.lowercase().contains(q) ||
                entry.hint.lowercase().contains(q) ||
                pageLabel.lowercase().contains(q)
        }
    }

    // ── Panel management ──────────────────────────────────────────────────────

    /** Moves the sidebar selection to [pageLabel], which in turn shows its panel. */
    private fun selectPage(pageLabel: String) {
        for (row in 0 until tree.rowCount) {
            val node = tree.getPathForRow(row).lastPathComponent as? DefaultMutableTreeNode
            if (node?.userObject == pageLabel) {
                tree.setSelectionRow(row)
                tree.scrollRowToVisible(row)
                return
            }
        }
    }

    private fun showPanel(name: String) {
        currentPanelName = name
        panelTitle.text = name

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

        if (panel is Renderable<*>) {
            @Suppress("UNCHECKED_CAST")
            (panel as Renderable<SettingsState>).render(settingsStore.state.value)
        }
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
        buildPanel(name).apply { applyComponentOrientation(this@SettingsDialog.componentOrientation) }

    private fun buildPanel(name: String): JPanel = when (name) {
        label("general") ->
            GeneralPanel(settingsStore, localizationManager)

        label("appearance") ->
            AppearancePanel(
                settingsStore, themeManager, localizationManager, scope,
                openEditor = { code -> openLanguageEditor(code) }
            )

        label("services") ->
            ServicesPanel(settingsStore, pluginManager, localizationManager, scope)

        label("plugins") ->
            PluginsPanel(iconManager, pluginManager, localizationManager, scope)

        label("hotkeys") ->
            KeyboardPanel(settingsStore, localizationManager, pauseGlobalHotkeys, resumeGlobalHotkeys)

        label("behavior") ->
            TranslationPanel(settingsStore, localizationManager)

        label("languages") ->
            LanguagesPanel(settingsStore, localizationManager, availableLanguages)

        label("layout") ->
            LayoutPanel(settingsStore, localizationManager)

        label("popups") ->
            PopupsPanel(settingsStore, localizationManager)

        else -> JPanel()
    }

    // ── Button bar ────────────────────────────────────────────────────────────

    private fun buildButtonBar(): JPanel {
        okButton = JButton(localizationManager.getString("common.ok")).apply {
            mnemonic = KeyEvent.VK_O
            addActionListener { onOk() }
        }
        val cancelButton = JButton(localizationManager.getString("common.cancel")).apply {
            mnemonic = KeyEvent.VK_C
            addActionListener { cancelAndClose() }
        }
        applyButton = JButton(localizationManager.getString("common.apply")).apply {
            mnemonic = KeyEvent.VK_A
            isEnabled = false
            addActionListener { settingsStore.dispatch(SettingsIntent.SaveChanges) }
        }
        val resetButton = JButton(
            localizationManager.getString("settings_dialog.reset_defaults_button")
        ).apply {
            mnemonic = KeyEvent.VK_R
            addActionListener { onReset() }
        }

        return JPanel(GridBagLayout()).apply {
            // Border applied by updateBorders()
            val gbc = GridBagConstraints().apply { gridy = 0; insets = Insets(10, 10, 10, 4) }
            gbc.gridx = 0; gbc.weightx = 1.0; gbc.anchor = GridBagConstraints.LINE_START
            add(resetButton, gbc)
            gbc.gridx = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.LINE_END
            add(okButton, gbc)
            gbc.gridx = 2; add(cancelButton, gbc)
            gbc.gridx = 3; gbc.insets = Insets(10, 4, 10, 10); add(applyButton, gbc)
        }
    }

    // ── State observation ─────────────────────────────────────────────────────

    private fun observeState() {
        scope.launch {
            settingsStore.state.collect { state ->
                withContext(Dispatchers.Swing) {
                    val baseTitle = localizationManager.getString("settings_dialog.title")
                    title = if (state.isDirty) "● $baseTitle" else baseTitle

                    applyButton.isEnabled = state.isDirty && !state.isSaving

                    currentPanelName?.let { name ->
                        val panel = panelCache[name]
                        if (panel is Renderable<*>) {
                            @Suppress("UNCHECKED_CAST")
                            (panel as Renderable<SettingsState>).render(state)
                        }
                    }
                }
            }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun onOk() {
        if (!settingsStore.state.value.isDirty) {
            dispose(); return
        }

        okButton.isEnabled = false
        okButton.text = localizationManager.getString("settings_dialog.saving")
        settingsStore.dispatch(SettingsIntent.SaveChanges)

        scope.launch {
            val event = settingsStore.events
                .filter { it is SettingsEvent.ShowMessage }
                .first() as SettingsEvent.ShowMessage

            withContext(Dispatchers.Swing) {
                if (event.type != NotificationType.ERROR) {
                    dispose()
                } else {
                    okButton.isEnabled = true
                    okButton.text = localizationManager.getString("common.ok")
                    JOptionPane.showMessageDialog(
                        this@SettingsDialog,
                        event.message,
                        localizationManager.getString("settings_dialog.save_failed_title"),
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun cancelAndClose() {
        settingsStore.dispatch(SettingsIntent.CancelChanges)
        dispose()
    }

    private fun onReset() {
        val result = JOptionPane.showConfirmDialog(
            this,
            localizationManager.getString("settings_dialog.reset_confirmation_message"),
            localizationManager.getString("settings_dialog.reset_confirmation_title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (result == JOptionPane.YES_OPTION) settingsStore.dispatch(SettingsIntent.ResetToDefaults)
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }

    private companion object {
        const val NAV_TREE = "tree"
        const val NAV_RESULTS = "results"
        const val NAV_EMPTY = "empty"
        const val PATH_SEPARATOR = "›"
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
        const val NEST_INDENT = 18
    }

    /**
     * Opens the language editor over this dialog and blocks until it closes.
     *
     * Modal on purpose: it writes the files this dialog is reading from, so letting both be used
     * at once would show settings that no longer match what is on disk.
     */
    private fun openLanguageEditor(languageCode: String?) {
        LanguageEditorDialog(
            owner = this,
            localizationManager = localizationManager,
            scope = scope,
            translateString = translateString,
            initialCode = languageCode
        ).isVisible = true
    }
}
