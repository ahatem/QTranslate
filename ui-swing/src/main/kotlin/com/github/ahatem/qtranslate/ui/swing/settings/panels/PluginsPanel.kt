package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.shared.util.blend
import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.*
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.util.role
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.applyForegroundColorFilter
import com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URI
import javax.accessibility.AccessibleContext
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet

/**
 * Settings panel for installed plugins.
 *
 * ### Layout
 * Master-detail split with a **fixed** action bar.
 *
 * ```
 * ┌─ leftPanel (200 px) ─┬─ rightPanel ─────────────────────────────────────┐
 * │  [list]              │  [detailScroll — scrollable content]              │
 * │                      │                                                   │
 * │  [Install]           ├───────────────────────────────────────────────────┤
 * │  Browse ↗            │  [actionBar — fixed, never scrolls]               │
 * └──────────────────────┴───────────────────────────────────────────────────┘
 * ```
 *
 * ### Why this implements [Scrollable]
 * [SettingsDialog] wraps every panel in a `JScrollPane`. If `PluginsPanel` is a plain
 * `JPanel`, the outer scroll pane makes the entire panel — including the action bar —
 * scrollable, so the buttons vanish when the dialog is small.
 *
 * ### Where one plugin's own settings are edited
 * In [DynamicPluginSettingsDialog], opened from the row's button or the action bar — not inside
 * this page. This page's job is choosing, installing and switching plugins; a plugin's own form is
 * a focused task, it is saved by its own button rather than by this dialog's Apply, and a plugin
 * can carry thirty fields. The form itself is [PluginSettingsForm], shared with the main window's
 * service menu, so the two entry points cannot drift apart.
 *
 * By implementing [Scrollable] with both `tracksViewportWidth` and
 * `tracksViewportHeight` returning `true`, the outer scroll pane sizes this panel to
 * exactly fill the viewport and shows no scrollbars. All scrolling is handled
 * internally: the plugin list and the detail area each have their own scroll pane.
 *
 * ### Why the detail content implements [Scrollable]
 * A plain `JPanel` inside a `JScrollPane` doesn't know its display width. `JTextArea`
 * with `lineWrap=true` only wraps when it has a width constraint — which requires the
 * containing panel to return `getScrollableTracksViewportWidth() = true`. Without this,
 * text expands the panel horizontally instead of wrapping.
 */
class PluginsPanel(
    private val iconManager: IconManager,
    private val pluginManager: PluginManager,
    private val localizationManager: LocalizationManager,
    private val scope: CoroutineScope
) : SettingsPanel(), Scrollable {

    // ── Scrollable — fills the outer JScrollPane viewport exactly ─────────────
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 16
    override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = 64
    override fun getScrollableTracksViewportWidth()  = true   // no horizontal outer scroll
    override fun getScrollableTracksViewportHeight() = true   // no vertical outer scroll → actionBar stays fixed

    // ── Internal state ────────────────────────────────────────────────────────
    /** The filter row above the list; its underline is rebuilt on every theme change. */
    private lateinit var listHeaderPanel: JPanel

    private val pluginListModel = DefaultListModel<PluginState>()
    private lateinit var pluginList: SettingsRowList<PluginState>
    private lateinit var listCards: JPanel
    private val listEmptyLabel = JLabel("", SwingConstants.CENTER)
    private val searchField = JTextField()
    private val categoryCombo = JComboBox(PluginCategory.entries.toTypedArray())
    private val resultCount = JLabel()
    private val leftPanel: JPanel          // holds the divider line; refreshed on theme change
    private val rightPanel: JPanel         // padded to sit clear of that line
    private val detailScroll: JScrollPane  // scrollable detail area
    private val actionBar = JPanel()       // fixed footer — never scrolls

    /** The list/detail divider. Held so the two halves can be mirrored for a right-to-left layout. */
    private lateinit var splitPane: SettingsSplitPane

    /** The install-and-drop strip under the list; its top rule is refreshed with the theme. */
    private lateinit var leftBottomPanel: JPanel
    private var selectedPlugin: PluginState? = null
    private var allPlugins: List<PluginState> = emptyList()

    /** Whether the plugin flow has produced anything yet, so the first page is always built. */
    private var hasEmittedPlugins = false

    private val themeListener = PropertyChangeListener { event ->
        if (event.propertyName == "lookAndFeel") SwingUtilities.invokeLater {
            refreshLeftBorder()
            refreshActionBarBorder()
            refreshPluginRows()
        }
    }

    /**
     * Recomputes everything whose side depends on the layout direction.
     *
     * The panel is built before the dialog flips it, so the constructor's call to
     * [refreshLeftBorder] always ran while this was still left-to-right. The divider was baked
     * onto the list's right edge and nothing revisited it, which left the line on the wrong side
     * of the list in Arabic. `applyComponentOrientation` sets the property and repaints; it does
     * not know that a border was derived from it, so the derivation has to run again here.
     */
    override fun applyComponentOrientation(orientation: ComponentOrientation) {
        super.applyComponentOrientation(orientation)
        // Guarded because a superclass constructor may reach this before the fields it touches
        // have been assigned.
        if (!::listHeaderPanel.isInitialized) return
        refreshLeftBorder()
        refreshPluginRows()
        // The pane mirrors by exchanging its children, so it only has to be told which way round
        // the interface now reads.
        if (::splitPane.isInitialized) splitPane.isMirrored = !orientation.isLeftToRight
    }

    init {
        // PluginsPanel overrides the SettingsPanel GridBag layout, but its outer edge still
        // matches SettingsPanel.EDGE (scaled) so the page doesn't read as a different density
        // from its siblings the moment the sidebar selects it.
        removeAll()
        layout = BorderLayout()
        val edge = UIScale.scale(SettingsPanel.EDGE)
        border = BorderFactory.createEmptyBorder(edge, UIScale.scale(8), UIScale.scale(8), edge)

        searchField.apply {
            putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                localizationManager.getString("settings_plugins.search_placeholder"))
            putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON,
                themedAppIcon(Icons.SEARCH, 15))
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = refreshPluginRows()
                override fun removeUpdate(e: DocumentEvent?) = refreshPluginRows()
                override fun changedUpdate(e: DocumentEvent?) = refreshPluginRows()
            })
        }
        searchField.toolTipText = localizationManager.getString("settings_plugins.search_shortcut_tooltip")

        // Ctrl+F puts the caret in the list's filter from anywhere on this page. Registered on the
        // page rather than on the dialog, so that here it searches plugins — the page's own
        // subject — while on every other page the dialog's binding searches the settings.
        registerKeyboardAction(
            {
                searchField.requestFocusInWindow()
                searchField.selectAll()
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
        )
        categoryCombo.apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                ): Component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    text = categoryName(value as? PluginCategory ?: PluginCategory.ALL)
                }
            }
            addActionListener { refreshPluginRows() }
        }
        val filterPanel = JPanel(GridLayout(1, 2, 6, 0)).apply {
            isOpaque = false
            add(searchField)
            add(categoryCombo)
        }
        listHeaderPanel = JPanel(BorderLayout(0, 7)).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 8, 10)
            add(filterPanel, BorderLayout.CENTER)
            add(resultCount.apply {
                foreground = UIManager.getColor("Label.disabledForeground")
                font = font.deriveFont(font.size - 1f)
            }, BorderLayout.SOUTH)
        }
        pluginList = SettingsRowList(
            model = pluginListModel,
            trailingHotspot = TRAILING_CONTROL,
            onRowActivated = { onConfigure(it) },
            onTrailingPressed = { plugin, _ -> toggleEnabled(plugin) },
        ).apply {
            cellRenderer = PluginRowRenderer()
            // Through the client property: `accessibleContext` is null until the component has one,
            // and a list built before it is showing would throw on the property itself.
            putClientProperty(
                AccessibleContext.ACCESSIBLE_NAME_PROPERTY,
                localizationManager.getString("settings_dialog_sidebar.plugins")
            )
            addListSelectionListener { event ->
                if (event.valueIsAdjusting) return@addListSelectionListener
                val plugin = selectedValue ?: return@addListSelectionListener
                if (plugin.id == selectedPlugin?.id) return@addListSelectionListener
                selectedPlugin = plugin
                rebuildDetail()
            }
        }

        val dropHandler = PluginJarTransferHandler()
        val listScroll = SettingsRowList.scrollOf(pluginList).apply {
            transferHandler = dropHandler
            viewport.transferHandler = dropHandler
        }
        listEmptyLabel.apply {
            foreground = UIManager.getColor("Label.disabledForeground")
            verticalAlignment = SwingConstants.TOP
            border = BorderFactory.createEmptyBorder(UIScale.scale(24), UIScale.scale(12), 0, UIScale.scale(12))
        }
        // The list and its "nothing here" message share the space: a `JList` with an empty model
        // has nothing to say, and the message has to be a component of its own.
        listCards = JPanel(CardLayout()).apply {
            add(listScroll, CARD_LIST)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(listEmptyLabel, BorderLayout.NORTH)
            }, CARD_NO_LIST)
        }

        val installBtn = JButton(
            localizationManager.getString("settings_plugins.install_plugin"),
            themedAppIcon(Icons.PLUGIN, 16)
        ).apply { addActionListener { onInstall() } }

        val browseLink = JLabel(
            "<html><u>${localizationManager.getString("settings_plugins.browse_on_github")}</u></html>"
        ).apply {
            cursor      = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground  = UIManager.getColor("Component.accentColor") ?: UIManager.getColor("Label.foreground")
            font        = font.deriveFont(font.size - 1f)
            toolTipText = "https://github.com/topics/qtranslate-plugin"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = runCatching {
                    Desktop.getDesktop().browse(URI("https://github.com/topics/qtranslate-plugin"))
                }.let {}
            })
        }

        val dropHint = JLabel(localizationManager.getString("settings_plugins.drop_hint"), SwingConstants.CENTER).apply {
            foreground = UIManager.getColor("Label.disabledForeground")
            font = font.deriveFont(font.size - 1f)
            icon = themedAppIcon(Icons.PLUGIN, 13)
            iconTextGap = 6
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createDashedBorder(
                    UIManager.getColor("Component.borderColor") ?: Color.GRAY,
                    1f, 4f, 3f, true
                ),
                BorderFactory.createEmptyBorder(6, 5, 6, 5)
            )
            transferHandler = dropHandler
        }
        leftBottomPanel = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            add(installBtn, BorderLayout.NORTH)
            add(dropHint, BorderLayout.CENTER)
            add(browseLink, BorderLayout.SOUTH)
        }

        leftPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(UIScale.scale(336), 0)
            minimumSize   = Dimension(280, 0)
            maximumSize   = Dimension(370, Int.MAX_VALUE)
            add(listHeaderPanel, BorderLayout.NORTH)
            add(listCards, BorderLayout.CENTER)
            add(leftBottomPanel, BorderLayout.SOUTH)
            transferHandler = dropHandler
        }
        UIManager.addPropertyChangeListener(themeListener)

        // ── Right: detail scroll + fixed action bar ───────────────────────────
        // Placeholder empty content — replaced by rebuildDetail()
        detailScroll = JScrollPane().apply {
            clearBorder()
            viewportBorder = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }

        rightPanel = JPanel(BorderLayout()).apply {
            add(detailScroll, BorderLayout.CENTER)
            add(actionBar,    BorderLayout.SOUTH)
        }

        refreshLeftBorder()

        // A split pane rather than two panels side by side: how wide the list should be is the
        // reader's call — a plugin name is long in one language and short in another — and the
        // divider is also where the rule between the two halves is drawn, rather than a matte
        // border baked onto the list's edge. FlatLaf draws the divider and its grip, so it follows
        // the theme like everything else.
        // The same split pane the workspace docks use: `MirroredSplitPane` arranges a right-to-left
        // interface by exchanging its children rather than by inverting the axis (which makes the
        // divider drag the wrong way), and `BorderSplitPaneUI` draws the hairline seam a region
        // boundary has instead of FlatLaf's grip dots. So the divider is also the rule between the
        // list and the detail pane that a matte border was painting before — and it can be dragged,
        // which is how a reader gives a long plugin name room in one language and takes it back in
        // another.
        splitPane = SettingsSplitPane.horizontal(leftPanel, rightPanel).apply {
            leadingResizeWeight = LIST_SHARE
            clearBorder()
            isMirrored          = !componentOrientation.isLeftToRight
        }
        add(splitPane, BorderLayout.CENTER)
        // Held until the pane has a width to divide: a proportion set on a zero-width split is
        // silently ignored, which is how a list ends up pinned to its minimum instead.
        splitPane.setLeadingProportion(LIST_SHARE)

        // Observe live plugin list
        scope.launch {
            pluginManager.plugins.collect { plugins ->
                SwingUtilities.invokeLater {
                    val previous = allPlugins
                    val prevId = selectedPlugin?.manifest?.id
                    allPlugins = plugins
                    selectedPlugin = plugins.firstOrNull { it.id == prevId } ?: plugins.firstOrNull()

                    // A settings save pushes an identical list through this flow; rebuilding the
                    // page for that would throw the reader out of whatever they were doing.
                    val changed = !hasEmittedPlugins ||
                        previous.map(::signatureOf) != plugins.map(::signatureOf)
                    hasEmittedPlugins = true
                    if (changed) refreshPluginRows()
                }
            }
        }

        rebuildDetail()
    }

    // ── Theme helpers ─────────────────────────────────────────────────────────

    /**
     * Draws the line dividing the list from the detail panel, and the gap either side of it.
     *
     * Rebuilt on every theme change rather than set once, because a border keeps the colour it was
     * given and a new look and feel does not revisit it — the line would hold the old theme's grey
     * against the new background. This is the same failure the service selector had.
     *
     * `MatteBorder` and `EmptyBorder` both take absolute sides and neither knows about layout
     * direction, so the sides are picked here: the line goes on the list's trailing edge and the
     * detail panel's padding on its leading edge, which puts both between the two panels whichever
     * way round the layout runs.
     */
    /**
     * Gives the two halves their padding and the filter row its rule.
     *
     * The line between the list and the detail pane is drawn by the split pane's own divider,
     * which is also what lets the divider be dragged; only the padding either side is set here,
     * and it has to be restored on a theme change because a border keeps the colour it was given.
     */
    private fun refreshLeftBorder() {
        val line = UIManager.getColor("Component.borderColor") ?: Color.GRAY
        val leftToRight = componentOrientation.isLeftToRight
        val gap = UIScale.scale(14)

        leftPanel.border = BorderFactory.createEmptyBorder()
        rightPanel.border = BorderFactory.createEmptyBorder(
            0,
            if (leftToRight) gap else 0,
            0,
            if (leftToRight) 0 else gap
        )

        // Separates the filter row from the list it filters. Without it the search box, the
        // category picker and the result count read as the first entry in the list rather than as
        // the controls above it.
        listHeaderPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, line),
            BorderFactory.createEmptyBorder(10, 10, 8, 10)
        )

        // And one under the list, so the install-and-drop strip reads as a footer of the list
        // rather than as two more entries at the end of it.
        leftBottomPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, line),
            BorderFactory.createEmptyBorder(8, 8, 10, 8)
        )

        leftPanel.revalidate()
        leftPanel.repaint()
    }

    private fun refreshActionBarBorder() {
        actionBar.border = BorderFactory.createEmptyBorder(10, 16, 4, 16)
    }

    private fun refreshPluginRows() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(::refreshPluginRows)
            return
        }
        val category = categoryCombo.selectedItem as? PluginCategory ?: PluginCategory.ALL
        val visiblePlugins = PluginPanelModel.filter(allPlugins, searchField.text, category)
        val selectedId = selectedPlugin?.id
        if (visiblePlugins.none { it.id == selectedId }) selectedPlugin = visiblePlugins.firstOrNull()

        pluginListModel.clear()
        visiblePlugins.forEach { pluginListModel.addElement(it) }

        // The selection follows the model, not the other way round: the listener above would
        // otherwise treat this write as the reader picking a row.
        val row = visiblePlugins.indexOfFirst { it.id == selectedPlugin?.id }
        if (row >= 0) {
            pluginList.selectedIndex = row
            pluginList.ensureIndexIsVisible(row)
        } else {
            pluginList.clearSelection()
        }

        val messageKey = if (allPlugins.isEmpty()) "settings_plugins.empty_title" else "settings_plugins.no_results"
        listEmptyLabel.text = localizationManager.getString(messageKey)
        (listCards.layout as CardLayout).show(
            listCards,
            if (visiblePlugins.isEmpty()) CARD_NO_LIST else CARD_LIST
        )

        resultCount.text = localizationManager.getString("settings_plugins.result_count")
            .format(visiblePlugins.size, allPlugins.size)
        rebuildDetail()
    }

    override fun removeNotify() {
        UIManager.removePropertyChangeListener(themeListener)
        super.removeNotify()
    }

    override fun addNotify() {
        super.addNotify()
        UIManager.removePropertyChangeListener(themeListener)
        UIManager.addPropertyChangeListener(themeListener)
    }

    /** The switch at a row's trailing edge: enable or disable that plugin, where it is clicked. */
    private fun toggleEnabled(plugin: PluginState) {
        if (plugin.status != PluginStatus.ENABLED && plugin.status != PluginStatus.DISABLED) return
        scope.launch {
            if (plugin.status == PluginStatus.ENABLED) pluginManager.disablePlugin(plugin.id)
            else pluginManager.enablePlugin(plugin.id)
        }
    }

    /**
     * One plugin as a list row.
     *
     * The components are built once and mutated: a renderer runs for every visible row on every
     * repaint — including the repaints a hover causes — and allocating a component tree per call
     * would make the list expensive for no benefit. Selection is not painted here at all; the list
     * paints it from `List.selection*`, which is what keeps it right in both themes and when the
     * list does not have the focus.
     */
    private inner class PluginRowRenderer : ListCellRenderer<PluginState> {
        private val iconCell = JLabel()
        private val nameLabel = JLabel().apply { font = font.deriveFont(Font.BOLD) }
        private val versionLabel = JLabel().apply { font = font.deriveFont(font.size - 1f) }
        private val switch = JCheckBox().apply { isOpaque = false }

        private val textColumn = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(nameLabel)
            add(versionLabel)
        }
        private val textRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(iconCell, BorderLayout.LINE_START)
            add(textColumn, BorderLayout.CENTER)
        }
        private val controls = JPanel(FlowLayout(FlowLayout.TRAILING, 2, 0)).apply { isOpaque = false }
        private val row = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            // The leading inset clears the selection pill *and* the accent bar inside it, so the
            // icon never sits on the marker.
            border = BorderFactory.createEmptyBorder(
                UIScale.scale(6), UIScale.scale(14), UIScale.scale(6), UIScale.scale(10)
            )
            add(textRow, BorderLayout.CENTER)
            add(controls, BorderLayout.LINE_END)
        }

        override fun getListCellRendererComponent(
            list: JList<out PluginState>,
            value: PluginState,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val primary = if (isSelected) UIManager.getColor("List.selectionForeground")
            else UIManager.getColor("Label.foreground")
            val secondary = if (isSelected) primary else SettingsRowList.secondaryForeground()

            iconCell.icon = rowIconFor(value)
            nameLabel.apply {
                text = value.manifest.name
                foreground = primary
                // A name longer than the row still has somewhere to be read in full.
                toolTipText = value.manifest.name
            }
            versionLabel.apply {
                text = "v${value.manifest.version}"
                foreground = secondary
            }

            controls.removeAll()
            if (value.status == PluginStatus.ENABLED || value.status == PluginStatus.DISABLED) {
                switch.apply {
                    // `this.` because the renderer's own `isSelected` parameter is in scope here.
                    this.isSelected = value.status == PluginStatus.ENABLED
                    toolTipText = if (this.isSelected) localizationManager.getString("settings_plugins.btn_disable")
                    else localizationManager.getString("settings_plugins.btn_enable")
                }
                controls.add(switch)
            } else {
                controls.add(buildStatusBadge(value.status))
            }

            row.toolTipText = null
            // A renderer is not part of the list's hierarchy, so it never receives the direction
            // the list was given: without this the switch sat on the wrong side in Arabic.
            row.applyComponentOrientation(list.componentOrientation)
            return row
        }
    }

    /** The plugin's own icon, cached: a renderer asks for it on every repaint of every row. */
    private fun rowIconFor(plugin: PluginState): Icon = rowIconCache.getOrPut(plugin.id) {
        val serviceId = plugin.services.firstOrNull()?.let(plugin::serviceIdOf)
        plugin.manifest.icon?.let { path -> serviceId?.let { iconManager.getIcon(it, path, 24, 24) } }
            ?: themedAppIcon(Icons.PLUGIN, 24)
    }

    private val rowIconCache = mutableMapOf<String, Icon>()

    private fun categoryName(category: PluginCategory): String = localizationManager.getString(
        when (category) {
            PluginCategory.ALL -> "settings_plugins.category_all"
            PluginCategory.TRANSLATORS -> "settings_plugins.category_translators"
            PluginCategory.DICTIONARIES -> "settings_plugins.category_dictionaries"
            PluginCategory.TTS -> "settings_plugins.category_tts"
            PluginCategory.OCR -> "settings_plugins.category_ocr"
            PluginCategory.SPELL_CHECKERS -> "settings_plugins.category_spell_checkers"
            PluginCategory.AI -> "settings_plugins.category_ai"
            PluginCategory.OTHER -> "settings_plugins.category_other"
        }
    )

    private fun themedAppIcon(path: String, size: Int): Icon =
        IconSet.load(path, size, size).applyForegroundColorFilter()

    // ── Detail rebuild ────────────────────────────────────────────────────────

    private fun rebuildDetail() {
        val plugin = selectedPlugin
        if (plugin == null) { showEmptyState(); return }

        // ── Scrollable info panel ─────────────────────────────────────────────
        // Implementing Scrollable with tracksViewportWidth=true is the critical
        // piece that gives JTextArea a width constraint, enabling text wrapping.
        val infoPanel = object : JPanel(GridBagLayout()), Scrollable {
            override fun getPreferredScrollableViewportSize() = preferredSize
            override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 16
            override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = 64
            override fun getScrollableTracksViewportWidth()  = true   // ← text wraps
            override fun getScrollableTracksViewportHeight() = false  // allow vertical scroll
        }.apply {
            border   = BorderFactory.createEmptyBorder(20, 22, 16, 22)
            isOpaque = true
            background = UIManager.getColor("Panel.background")
        }

        val g = GridBag(infoPanel, horizontalGap = 0, verticalGap = 0)

        // ─ Plugin icon (small, left of name) + name + status badge ───────────
        val pluginIconPath = plugin.manifest.icon
        val serviceId      = plugin.services.firstOrNull()?.let(plugin::serviceIdOf)
        val headerIcon: Icon = if (pluginIconPath != null && serviceId != null)
            iconManager.getIcon(serviceId, pluginIconPath, 24, 24)
        else
            themedAppIcon(Icons.PLUGIN, 24)

        val nameLabel = JLabel(plugin.manifest.name).apply {
            font = font.deriveFont(Font.BOLD, font.size + 2f)
        }
        val statusBadge = buildStatusBadge(plugin.status)

        val headerRow = JPanel(FlowLayout(FlowLayout.LEADING, 6, 0)).apply {
            isOpaque = false
            add(JLabel(headerIcon))
            add(nameLabel)
            // What the plugin can do, where there is room to say it: a list row is 290 px wide, so
            // spelling the roles out as text would be cut there mid-word.
            plugin.services.mapNotNull { it.role }.distinct().forEach { role ->
                add(JLabel(roleIcon(role, ROLE_GLYPH)).apply {
                    toolTipText = role.readableName(localizationManager)
                })
            }
            add(statusBadge)
        }
        val metaLabel = JLabel("v${plugin.manifest.version}  ·  ${plugin.manifest.author}").apply {
            foreground = UIManager.getColor("Label.disabledForeground")
        }

        g.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(0, 0, 2, 0).add(headerRow)
        g.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(0, 30, 0, 0).add(metaLabel)

        val metadata = JPanel(GridLayout(0, 2, 18, 8)).apply {
            isOpaque = false
            add(buildMetadataItem(localizationManager.getString("settings_plugins.detail_category"),
                PluginPanelModel.categories(plugin).joinToString(", ") { categoryName(it) }))
            add(buildMetadataItem(localizationManager.getString("settings_plugins.detail_api"),
                "QTranslate API ${plugin.manifest.minApiVersion}+"))
            add(buildMetadataItem(localizationManager.getString("settings_plugins.detail_file"),
                File(plugin.jarPath).name, plugin.jarPath))
            add(buildMetadataItem(localizationManager.getString("settings_plugins.detail_languages"),
                supportedLanguagesSummary(plugin)))
        }
        g.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(14, 0, 0, 0).add(metadata)

        // ─ Description ────────────────────────────────────────────────────────
        if (plugin.manifest.description.isNotBlank()) {
            g.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
                .insets(14, 0, 0, 0)
                .add(buildDivider())

            // Non-editable JTextArea styled as a label — wraps automatically
            // because infoPanel tracks viewport width.
            val desc = JTextArea(plugin.manifest.description).apply {
                isEditable    = false
                lineWrap      = true
                wrapStyleWord = true
                isOpaque      = false
                isFocusable   = false
                border        = BorderFactory.createEmptyBorder()
                foreground    = UIManager.getColor("Label.foreground")
                font          = UIManager.getFont("Label.font") ?: font
            }
            g.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
                .insets(10, 0, 0, 0).add(desc)
        }

        // ─ Services ───────────────────────────────────────────────────────────
        if (plugin.services.isNotEmpty()) {
            g.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
                .insets(14, 0, 0, 0)
                .add(buildDivider())

            val serviceLabel = JLabel(localizationManager.getString("settings_plugins.section_services")).apply {
                font       = font.deriveFont(Font.BOLD)
                foreground = UIManager.getColor("Label.disabledForeground")
            }
            g.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
                .insets(10, 0, 6, 0).add(serviceLabel)

            val services = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                plugin.services.forEach { svc ->
                    add(buildServiceLine(plugin.serviceIdOf(svc), svc))
                }
            }
            g.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
                .insets(0, 0, 0, 0).add(services)
        }

        // ─ Error ──────────────────────────────────────────────────────────────
        if (plugin.status == PluginStatus.FAILED && plugin.lastError != null) {
            val errorColor = UIManager.getColor("Actions.Red") ?: Color(0xE53935)

            g.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
                .insets(14, 0, 0, 0)
                .add(buildDivider())

            val errLabel = JLabel(localizationManager.getString("settings_plugins.plugin_error_label")).apply {
                font       = font.deriveFont(Font.BOLD)
                foreground = errorColor
            }
            g.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
                .insets(10, 0, 6, 0).add(errLabel)

            val errArea = JTextArea(plugin.lastError!!.message).apply {
                isEditable    = false
                lineWrap      = true
                wrapStyleWord = true
                isOpaque      = false
                isFocusable   = false
                border        = BorderFactory.createEmptyBorder()
                foreground    = errorColor
                font          = UIManager.getFont("Label.font") ?: font
            }
            g.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
                .insets(0, 0, 0, 0).add(errArea)
        }

        // Push content to top
        g.nextRow().spanLine().weightX(1.0).weightY(1.0).fill(GridBagConstraints.BOTH)
            .add(Box.createVerticalGlue())

        detailScroll.setViewportView(infoPanel)
        // A component added after the page was given its direction starts out left-to-right: the
        // orientation cascade ran when this panel was built, and everything rebuilt since — the
        // details, the rows, the action bar — never saw it. In Arabic that showed up as a detail
        // pane and a list whose contents were laid out the wrong way round.
        infoPanel.applyComponentOrientation(componentOrientation)
        detailScroll.revalidate()
        detailScroll.repaint()

        // ── Action bar ────────────────────────────────────────────────────────
        rebuildActionBar(plugin)
        actionBar.applyComponentOrientation(componentOrientation)
    }

    private fun showEmptyState() {
        val emptyPanel = JPanel(GridBagLayout()).apply {
            isOpaque   = false
            val gbc = GridBagConstraints().apply {
                gridx  = 0; gridy = GridBagConstraints.RELATIVE
                anchor = GridBagConstraints.CENTER
                insets = Insets(6, 0, 6, 0)
            }
            val pkgIcon = runCatching {
                val ico = IconSet.load(Icons.PLUGIN, 36, 36)
                ico.colorFilter = FlatSVGIcon.ColorFilter { UIManager.getColor("Label.disabledForeground") ?: Color.GRAY }
                ico as Icon
            }.getOrNull()
            if (pkgIcon != null) add(JLabel(pkgIcon), gbc)
            val titleKey = when {
                allPlugins.isEmpty() -> "settings_plugins.empty_title"
                searchField.text.isNotBlank() || categoryCombo.selectedItem != PluginCategory.ALL -> "settings_plugins.no_results"
                else -> "settings_plugins.empty_selection_hint"
            }
            add(JLabel(localizationManager.getString(titleKey)).apply {
                foreground = UIManager.getColor("Label.disabledForeground")
            }, gbc)
            if (allPlugins.isEmpty()) add(JLabel(localizationManager.getString("settings_plugins.empty_description")).apply {
                foreground = UIManager.getColor("Label.disabledForeground")
                font = font.deriveFont(font.size - 1f)
            }, gbc)
        }

        detailScroll.setViewportView(emptyPanel)
        detailScroll.revalidate()
        detailScroll.repaint()

        actionBar.removeAll()
        actionBar.isVisible = false
        actionBar.revalidate()
    }

    private fun rebuildActionBar(plugin: PluginState) {
        actionBar.removeAll()
        actionBar.layout = BorderLayout()

        refreshActionBarBorder()

        val leftGroup  = JPanel(FlowLayout(FlowLayout.LEADING,  6, 0)).apply { isOpaque = false }
        val rightGroup = JPanel(FlowLayout(FlowLayout.TRAILING, 6, 0)).apply { isOpaque = false }

        when (plugin.status) {
            PluginStatus.ENABLED -> {
                leftGroup.add(JButton(localizationManager.getString("settings_plugins.btn_settings")).apply {
                    addActionListener { onConfigure(plugin) }
                })
                rightGroup.add(JButton(localizationManager.getString("settings_plugins.btn_disable")).apply {
                    addActionListener { scope.launch { pluginManager.disablePlugin(plugin.manifest.id) } }
                })
            }
            PluginStatus.DISABLED -> {
                leftGroup.add(JButton(localizationManager.getString("settings_plugins.btn_settings")).apply {
                    addActionListener { onConfigure(plugin) }
                })
                rightGroup.add(JButton(localizationManager.getString("settings_plugins.btn_enable")).apply {
                    addActionListener { scope.launch { pluginManager.enablePlugin(plugin.manifest.id) } }
                })
            }
            PluginStatus.AWAITING_VERIFICATION -> {
                rightGroup.add(JButton(localizationManager.getString("settings_plugins.btn_accept_update")).apply {
                    toolTipText = localizationManager.getString("settings_plugins.tip_accept_update")
                    addActionListener { scope.launch { pluginManager.resolveAsUpdate(plugin.manifest.id) } }
                })
                rightGroup.add(JButton(localizationManager.getString("settings_plugins.btn_clean_install")).apply {
                    toolTipText = localizationManager.getString("settings_plugins.tip_clean_install")
                    addActionListener { scope.launch { pluginManager.resolveAsCleanInstall(plugin.manifest.id) } }
                })
            }
            PluginStatus.FAILED -> Unit
        }

        // A bundled plugin's JAR belongs to the installation, so uninstalling it could not be honoured.
        // Disable is offered instead, which genuinely turns it off.
        if (plugin.uninstallable) {
            rightGroup.add(JButton(localizationManager.getString("settings_plugins.btn_uninstall")).apply {
                foreground = UIManager.getColor("Actions.Red") ?: Color.RED
                addActionListener { onUninstall(plugin) }
            })
        }

        actionBar.add(leftGroup,  BorderLayout.LINE_START)
        actionBar.add(rightGroup, BorderLayout.LINE_END)
        actionBar.isVisible = true

        actionBar.revalidate()
        actionBar.repaint()
    }

    // ── Widget builders ───────────────────────────────────────────────────────

    /**
     * A theme-aware 1 px horizontal divider that never collapses.
     *
     * [JSeparator] can report a preferred height of 0 in some LAF configurations,
     * causing GridBagLayout to squish it to invisible. This component reads its
     * color from [UIManager] at paint time (so it's always correct after a theme
     * switch) and has an explicit minimum size of 1 px so it never disappears.
     */
    private fun buildDivider(): JComponent = object : JComponent() {
        init {
            preferredSize = Dimension(0, 1)
            minimumSize   = Dimension(0, 1)
        }
        override fun paintComponent(g: Graphics) {
            g.color = UIManager.getColor("Separator.foreground")
                ?: UIManager.getColor("Component.borderColor")
                ?: Color.GRAY
            g.fillRect(0, 0, width, 1)
        }
    }

    private fun buildStatusBadge(status: PluginStatus): JLabel {
        // The theme's own semantic colours where it has them. A badge is text on a tint of its own
        // colour rather than white on a saturated fill: that keeps it legible in both the light and
        // the dark theme, and it darkens or lightens with the theme instead of being frozen.
        val (text, accent) = when (status) {
            PluginStatus.ENABLED ->
                localizationManager.getString("settings_plugins.status_enabled") to
                    (UIManager.getColor("Actions.Green") ?: Color(0x2E7D32))
            PluginStatus.DISABLED ->
                localizationManager.getString("settings_plugins.status_disabled") to
                    (UIManager.getColor("Component.borderColor") ?: Color(0x757575))
            PluginStatus.FAILED ->
                localizationManager.getString("settings_plugins.status_failed") to
                    (UIManager.getColor("Actions.Red") ?: Color(0xC62828))
            PluginStatus.AWAITING_VERIFICATION ->
                localizationManager.getString("settings_plugins.status_verification") to
                    (UIManager.getColor("Actions.Yellow") ?: Color(0xE65100))
        }
        return JLabel(" $text ").apply {
            foreground = SettingsVisuals.atLeast(accent, SettingsVisuals.background(), 4.5f)
            background = accent.blend(SettingsVisuals.background(), 0.85f)
            isOpaque   = true
            font       = font.deriveFont(Font.BOLD, font.size - 1.5f)
            border     = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            putClientProperty("FlatLaf.style", "arc: 8")
        }
    }

    private fun buildServiceLine(serviceId: String, service: Service): JComponent = JPanel(BorderLayout(8, 0)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 27)
        border = BorderFactory.createEmptyBorder(3, 0, 3, 0)
        val serviceIcon = service.iconPath?.let { iconManager.getIcon(serviceId, it, 15, 15) }
            ?: service.role?.let(::roleIcon)
            ?: themedAppIcon(Icons.PLUGIN, 15)
        add(JLabel(service.name, serviceIcon, SwingConstants.LEADING).apply {
            font = font.deriveFont(Font.BOLD, font.size - 0.5f)
            iconTextGap = 7
        }, BorderLayout.CENTER)
        val typeName = service.role?.readableName(localizationManager)
        if (typeName != null && typeName != service.name) add(JLabel(typeName).apply {
            foreground = UIManager.getColor("Label.disabledForeground")
            font = font.deriveFont(font.size - 1f)
        }, BorderLayout.LINE_END)
    }

    private fun roleIcon(type: ServiceRole, size: Int = 15): Icon = themedAppIcon(
        when (type) {
            ServiceRole.TRANSLATOR -> Icons.TRANSLATE
            ServiceRole.TTS -> Icons.SPEAK
            ServiceRole.OCR -> Icons.OCR
            ServiceRole.SPELL_CHECKER -> Icons.CHECK
            ServiceRole.DICTIONARY -> Icons.DICTIONARY
            ServiceRole.SUMMARIZER -> Icons.SUMMARIZE
            ServiceRole.REWRITER -> Icons.EDIT
            // No service declares this yet; the generic icon is a placeholder until one does.
            ServiceRole.IMAGE_SEARCH -> Icons.SEARCH
        },
        15
    )

    private fun buildMetadataItem(label: String, value: String, tooltip: String? = null): JComponent = JPanel(BorderLayout(0, 2)).apply {
        isOpaque = false
        add(JLabel(label).apply {
            font = font.deriveFont(font.size - 1f)
            foreground = UIManager.getColor("Label.disabledForeground")
        }, BorderLayout.NORTH)
        add(JTextArea(value).apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            rows = 1
            columns = 18
            clearBorder()
            font = UIManager.getFont("Label.font").deriveFont(Font.BOLD)
            foreground = UIManager.getColor("Label.foreground")
            toolTipText = tooltip
        }, BorderLayout.CENTER)
    }

    private fun supportedLanguagesSummary(plugin: PluginState): String {
        if (plugin.services.isEmpty()) return localizationManager.getString("settings_plugins.not_available")
        if (plugin.services.any { it.supportedLanguages is SupportedLanguages.All }) {
            return localizationManager.getString("settings_plugins.languages_all")
        }
        if (plugin.services.any { it.supportedLanguages is SupportedLanguages.Dynamic }) {
            return localizationManager.getString("settings_plugins.languages_dynamic")
        }
        val languages = plugin.services.flatMap { service ->
            (service.supportedLanguages as? SupportedLanguages.Specific)?.languages.orEmpty()
        }.distinctBy { it.tag }
        return localizationManager.getString("settings_plugins.languages_count").format(languages.size)
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun onInstall() {
        val chooser = JFileChooser().apply {
            dialogTitle = localizationManager.getString("settings_plugins.install_dialog_title")
            fileFilter  = FileNameExtensionFilter(
                localizationManager.getString("settings_plugins.install_dialog_filter"), "jar")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        installPlugin(chooser.selectedFile)
    }

    private fun installPlugin(file: File) {
        scope.launch {
            pluginManager.installPlugin(file).fold(
                success = {
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(this@PluginsPanel,
                            localizationManager.getString("settings_plugins.install_success_msg"),
                            localizationManager.getString("settings_plugins.install_success_title"),
                            JOptionPane.INFORMATION_MESSAGE)
                    }
                },
                failure = { err ->
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(this@PluginsPanel, err,
                            localizationManager.getString("settings_plugins.install_fail_title"),
                            JOptionPane.ERROR_MESSAGE)
                    }
                }
            )
        }
    }

    private fun onUninstall(plugin: PluginState) {
        // Re-checked here because the button is rebuilt from a snapshot that may predate a change.
        if (pluginManager.isBundled(plugin.manifest.id)) return
        val confirmed = JOptionPane.showConfirmDialog(
            this,
            localizationManager.getString("settings_plugins.uninstall_confirm_msg").format(plugin.manifest.name),
            localizationManager.getString("settings_plugins.uninstall_confirm_title"),
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION
        if (confirmed) scope.launch { pluginManager.uninstallPlugin(plugin.manifest.id) }
    }

    private fun onConfigure(plugin: PluginState) {
        scope.launch {
            val model    = pluginManager.getPluginSettingsModel(plugin.manifest.id)
            val instance = pluginManager.getPluginSettingsInstance(plugin.manifest.id)
            SwingUtilities.invokeLater {
                if (model == null || model.schema.isEmpty()) {
                    // A message rather than a disabled button: this is the only way in, so the
                    // reader should learn there is nothing here by asking, not by finding the
                    // control greyed out with no explanation.
                    JOptionPane.showMessageDialog(this@PluginsPanel,
                        localizationManager.getString("settings_plugins.no_settings_msg"),
                        plugin.manifest.name, JOptionPane.INFORMATION_MESSAGE)
                    return@invokeLater
                }
                DynamicPluginSettingsDialog(
                    owner               = SwingUtilities.getWindowAncestor(this@PluginsPanel),
                    pluginName          = plugin.manifest.name,
                    localizationManager = localizationManager,
                    settingsModel       = model,
                    settingsInstance    = instance,
                    onSave              = { values, clearedSecrets ->
                        pluginManager.applySettingsFromMap(plugin.manifest.id, values, clearedSecrets)
                    },
                    // Offered only where there is something to check; applying first is what makes
                    // it a test of the values on screen rather than of the ones saved last time.
                    onTestConnection    = if (plugin.services.isEmpty()) null else { values ->
                        testConnection(plugin.manifest.id, plugin.manifest.name, values)
                    },
                    // Opened from Settings, this window sits in front of a dialog whose Apply is
                    // right behind it and does not govern a plugin's values. Say so where they are.
                    notice              = localizationManager.getString("settings_plugins.settings_hint"),
                ).isVisible = true
            }
        }
    }

    /**
     * Applies what is on screen, then asks each service whether it works.
     *
     * Applied first so the check uses the key that was just typed rather than the one saved last
     * time; a failed apply is reported as the result instead of validating the stored values and
     * presenting that as a test of what the reader can see.
     */
    private suspend fun testConnection(
        pluginId: String,
        pluginName: String,
        values: Map<String, String>,
    ): List<ServiceValidation> {
        val error = pluginManager.applySettingsFromMap(pluginId, values).getError()
        if (error != null) {
            return listOf(ServiceValidation(serviceId = "", serviceName = pluginName, error = error))
        }
        return pluginManager.validateServices(pluginId)
    }

    /**
     * Everything this page displays about a plugin, in one string.
     *
     * A settings save pushes the whole plugin list through the flow again with nothing about the
     * list actually changed. Rebuilding the page for that would cost the reader the values they
     * just typed, the confirmation that they were saved, and their place in the list — so the
     * rebuild is spent only where something visible really moved.
     */
    private fun signatureOf(plugin: PluginState): String = listOf(
        plugin.id,
        plugin.manifest.version,
        plugin.status.name,
        plugin.jarPath,
        plugin.lastError?.message.orEmpty(),
        plugin.services.joinToString(",") { "${it.name}|${it.role}" },
    ).joinToString("|")

    override fun render(state: SettingsState) = Unit

    private inner class PluginJarTransferHandler : TransferHandler() {
        override fun canImport(support: TransferSupport): Boolean =
            support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            val files = runCatching {
                @Suppress("UNCHECKED_CAST")
                support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
            }.getOrNull().orEmpty()
            val jars = files.filter { it.isFile && it.extension.equals("jar", true) }
            if (jars.isEmpty()) return false
            jars.forEach(::installPlugin)
            return true
        }
    }

    private companion object {
        /** Width reserved at a row's trailing edge for its enable switch, in logical pixels. */
        const val TRAILING_CONTROL = 26

        /** The list's share of the width: an honest starting point that the reader can drag. */
        const val LIST_SHARE = 0.34

        /** Size of the role glyphs drawn beside a plugin's name in the detail pane. */
        const val ROLE_GLYPH = 13

        /** Cards of the list area: the list itself, and the "nothing matches" message. */
        const val CARD_LIST    = "list"
        const val CARD_NO_LIST = "noList"
    }
}

// ── Extension ─────────────────────────────────────────────────────────────────

fun ServiceRole.readableName(localizationManager: LocalizationManager): String =
    when (this) {
        ServiceRole.TRANSLATOR    -> localizationManager.getString("settings_services.translator").removeSuffix(":")
        ServiceRole.TTS           -> localizationManager.getString("settings_services.tts").removeSuffix(":")
        ServiceRole.OCR           -> localizationManager.getString("settings_services.ocr").removeSuffix(":")
        ServiceRole.SPELL_CHECKER -> localizationManager.getString("settings_services.spell_checker").removeSuffix(":")
        ServiceRole.DICTIONARY    -> localizationManager.getString("settings_services.dictionary").removeSuffix(":")
        ServiceRole.SUMMARIZER    -> localizationManager.getString("settings_services.summarizer").removeSuffix(":")
        ServiceRole.REWRITER      -> localizationManager.getString("settings_services.rewriter").removeSuffix(":")
        ServiceRole.IMAGE_SEARCH  -> localizationManager.getString("settings_services.image_search").removeSuffix(":")
    }
