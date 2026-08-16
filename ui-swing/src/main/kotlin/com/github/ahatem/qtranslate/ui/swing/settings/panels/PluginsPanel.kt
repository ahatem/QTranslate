package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.*
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.util.type
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.applyForegroundColorFilter
import com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URI
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter

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

    private val pluginRows = JPanel()
    private val searchField = JTextField()
    private val categoryCombo = JComboBox(PluginCategory.entries.toTypedArray())
    private val resultCount = JLabel()
    private val leftPanel: JPanel          // holds the divider line; refreshed on theme change
    private val rightPanel: JPanel         // padded to sit clear of that line
    private val detailScroll: JScrollPane  // scrollable detail area
    private val actionBar = JPanel()       // fixed footer — never scrolls
    private var selectedPlugin: PluginState? = null
    private var allPlugins: List<PluginState> = emptyList()

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
    }

    init {
        // PluginsPanel overrides the SettingsPanel GridBag layout
        removeAll()
        layout = BorderLayout()
        border = BorderFactory.createEmptyBorder(10, 8, 8, 10)

        searchField.apply {
            putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                localizationManager.getString("settings_plugins.search_placeholder"))
            putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON,
                themedAppIcon("icons/lucide/search.svg", 15))
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = refreshPluginRows()
                override fun removeUpdate(e: DocumentEvent?) = refreshPluginRows()
                override fun changedUpdate(e: DocumentEvent?) = refreshPluginRows()
            })
        }
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
        pluginRows.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIManager.getColor("Panel.background")
            border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
        }

        val dropHandler = PluginJarTransferHandler()
        val listScroll = JScrollPane(pluginRows).apply {
            clearBorder()
            viewportBorder = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
            transferHandler = dropHandler
            viewport.transferHandler = dropHandler
        }

        val installBtn = JButton(
            localizationManager.getString("settings_plugins.install_plugin"),
            themedAppIcon("icons/lucide/package.svg", 16)
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
            icon = themedAppIcon("icons/lucide/package.svg", 13)
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
        val leftBottom = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(8, 8, 10, 8)
            add(installBtn, BorderLayout.NORTH)
            add(dropHint, BorderLayout.CENTER)
            add(browseLink, BorderLayout.SOUTH)
        }

        leftPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(UIScale.scale(320), 0)
            minimumSize   = Dimension(270, 0)
            maximumSize   = Dimension(350, Int.MAX_VALUE)
            add(listHeaderPanel, BorderLayout.NORTH)
            add(listScroll, BorderLayout.CENTER)
            add(leftBottom, BorderLayout.SOUTH)
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

        add(leftPanel,  BorderLayout.LINE_START)
        add(rightPanel, BorderLayout.CENTER)

        // Observe live plugin list
        scope.launch {
            pluginManager.plugins.collect { plugins ->
                SwingUtilities.invokeLater {
                    val prevId = selectedPlugin?.manifest?.id
                    allPlugins = plugins
                    selectedPlugin = plugins.firstOrNull { it.id == prevId } ?: plugins.firstOrNull()
                    refreshPluginRows()
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
    private fun refreshLeftBorder() {
        val line = UIManager.getColor("Component.borderColor") ?: Color.GRAY
        val leftToRight = componentOrientation.isLeftToRight
        val gap = UIScale.scale(14)

        // The list's contents carry their own insets, so the line sits at the panel edge.
        leftPanel.border = BorderFactory.createMatteBorder(
            0,
            if (leftToRight) 0 else 1,
            0,
            if (leftToRight) 1 else 0,
            line
        )
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

        pluginRows.removeAll()
        if (visiblePlugins.isEmpty()) {
            val messageKey = if (allPlugins.isEmpty()) "settings_plugins.empty_title" else "settings_plugins.no_results"
            pluginRows.add(JLabel(localizationManager.getString(messageKey), SwingConstants.CENTER).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = UIManager.getColor("Label.disabledForeground")
                border = BorderFactory.createEmptyBorder(30, 12, 12, 12)
            })
        } else {
            visiblePlugins.forEach {
                pluginRows.add(buildPluginRow(it))
                pluginRows.add(Box.createVerticalStrut(3))
            }
            pluginRows.add(Box.createVerticalGlue())
        }
        resultCount.text = localizationManager.getString("settings_plugins.result_count")
            .format(visiblePlugins.size, allPlugins.size)
        pluginRows.revalidate()
        pluginRows.repaint()
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

    private fun buildPluginRow(plugin: PluginState): JComponent {
        val selected = plugin.id == selectedPlugin?.id
        val row = object : JPanel(BorderLayout(8, 0)) {
            override fun paintComponent(graphics: Graphics) {
                if (selected) {
                    val g2 = graphics.create() as Graphics2D
                    g2.color = UIManager.getColor("List.selectionBackground")
                        ?: UIManager.getColor("Component.focusColor")
                        ?: Color(0x3D5A80)
                    g2.fillRoundRect(0, 0, width, height, 8, 8)
                    g2.dispose()
                }
                super.paintComponent(graphics)
            }
        }.apply {
            maximumSize = Dimension(Int.MAX_VALUE, 62)
            preferredSize = Dimension(UIScale.scale(290), UIScale.scale(62))
            isOpaque = false
            border = BorderFactory.createEmptyBorder(7, 9, 7, 7)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    selectedPlugin = plugin
                    refreshPluginRows()
                }
            })
        }
        val foreground = if (selected) UIManager.getColor("List.selectionForeground") else UIManager.getColor("Label.foreground")
        val serviceId = plugin.services.firstOrNull()?.let(plugin::serviceIdOf)
        val icon = plugin.manifest.icon?.let { path -> serviceId?.let { iconManager.getIcon(it, path, 24, 24) } }
            ?: themedAppIcon("icons/lucide/package.svg", 24)
        val categories = PluginPanelModel.categories(plugin).joinToString(" · ") { categoryName(it) }
        val text = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JLabel(plugin.manifest.name, icon, SwingConstants.LEADING).apply {
                font = font.deriveFont(Font.BOLD)
                this.foreground = foreground
                iconTextGap = 8
            })
            add(JLabel("v${plugin.manifest.version} · $categories").apply {
                font = font.deriveFont(font.size - 1f)
                this.foreground = if (selected) foreground else UIManager.getColor("Label.disabledForeground")
                // Indented to sit under the plugin's name rather than under its icon. That is a
                // leading-edge inset, so it swaps sides with the layout: written absolutely, the
                // line hung off the wrong edge in a right-to-left interface and stopped lining up
                // with the name it belongs to.
                //
                // The direction is read from the panel, not from this label. A component that has
                // not been added to anything yet reports ComponentOrientation.UNKNOWN, whose
                // isLeftToRight is true, so asking the label always answered left-to-right and the
                // indent never moved.
                border = if (this@PluginsPanel.componentOrientation.isLeftToRight) {
                    BorderFactory.createEmptyBorder(3, NAME_INDENT, 0, 0)
                } else {
                    BorderFactory.createEmptyBorder(3, 0, 0, NAME_INDENT)
                }
            })
        }
        val controls = JPanel(FlowLayout(FlowLayout.TRAILING, 2, 0)).apply {
            isOpaque = false
            if (plugin.status == PluginStatus.ENABLED || plugin.status == PluginStatus.DISABLED) {
                add(JCheckBox().apply {
                    isSelected = plugin.status == PluginStatus.ENABLED
                    toolTipText = if (isSelected) localizationManager.getString("settings_plugins.btn_disable")
                        else localizationManager.getString("settings_plugins.btn_enable")
                    isOpaque = false
                    addActionListener {
                        isEnabled = false
                        scope.launch {
                            if (isSelected) pluginManager.enablePlugin(plugin.id) else pluginManager.disablePlugin(plugin.id)
                        }
                    }
                })
                add(JButton(themedAppIcon("icons/lucide/settings.svg", 15)).apply {
                    putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
                    toolTipText = localizationManager.getString("settings_plugins.btn_configure")
                    addActionListener { onConfigure(plugin) }
                })
            } else {
                add(buildStatusBadge(plugin.status))
            }
        }
        row.add(text, BorderLayout.CENTER)
        row.add(controls, BorderLayout.LINE_END)
        return row
    }

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
        FlatSVGIcon(path, size, size, javaClass.classLoader).applyForegroundColorFilter()

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
            themedAppIcon("icons/lucide/package.svg", 24)

        val nameLabel = JLabel(plugin.manifest.name).apply {
            font = font.deriveFont(Font.BOLD, font.size + 2f)
        }
        val statusBadge = buildStatusBadge(plugin.status)

        val headerRow = JPanel(FlowLayout(FlowLayout.LEADING, 6, 0)).apply {
            isOpaque = false
            add(JLabel(headerIcon))
            add(nameLabel)
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
        detailScroll.revalidate()
        detailScroll.repaint()

        // ── Action bar ────────────────────────────────────────────────────────
        rebuildActionBar(plugin)
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
                val ico = FlatSVGIcon("icons/lucide/package.svg", 36, 36, javaClass.classLoader)
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
                leftGroup.add(JButton(localizationManager.getString("settings_plugins.btn_configure")).apply {
                    addActionListener { onConfigure(plugin) }
                })
                rightGroup.add(JButton(localizationManager.getString("settings_plugins.btn_disable")).apply {
                    addActionListener { scope.launch { pluginManager.disablePlugin(plugin.manifest.id) } }
                })
            }
            PluginStatus.DISABLED -> {
                leftGroup.add(JButton(localizationManager.getString("settings_plugins.btn_configure")).apply {
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

        rightGroup.add(JButton(localizationManager.getString("settings_plugins.btn_uninstall")).apply {
            foreground = UIManager.getColor("Actions.Red") ?: Color.RED
            addActionListener { onUninstall(plugin) }
        })

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
        val (text, bg) = when (status) {
            PluginStatus.ENABLED               -> localizationManager.getString("settings_plugins.status_enabled")      to Color(0x2E7D32)
            PluginStatus.DISABLED              -> localizationManager.getString("settings_plugins.status_disabled")     to Color(0x757575)
            PluginStatus.FAILED                -> localizationManager.getString("settings_plugins.status_failed")       to Color(0xC62828)
            PluginStatus.AWAITING_VERIFICATION -> localizationManager.getString("settings_plugins.status_verification") to Color(0xE65100)
        }
        return JLabel(" $text ").apply {
            foreground = Color.WHITE
            background = bg
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
            ?: service.type?.let(::capabilityIcon)
            ?: themedAppIcon("icons/lucide/package.svg", 15)
        add(JLabel(service.name, serviceIcon, SwingConstants.LEADING).apply {
            font = font.deriveFont(Font.BOLD, font.size - 0.5f)
            iconTextGap = 7
        }, BorderLayout.CENTER)
        val typeName = service.type?.readableName(localizationManager)
        if (typeName != null && typeName != service.name) add(JLabel(typeName).apply {
            foreground = UIManager.getColor("Label.disabledForeground")
            font = font.deriveFont(font.size - 1f)
        }, BorderLayout.LINE_END)
    }

    private fun capabilityIcon(type: ServiceType): Icon = themedAppIcon(
        when (type) {
            ServiceType.TRANSLATOR -> "icons/lucide/languages.svg"
            ServiceType.TTS -> "icons/lucide/volume.svg"
            ServiceType.OCR -> "icons/lucide/scan-text.svg"
            ServiceType.SPELL_CHECKER -> "icons/lucide/check.svg"
            ServiceType.DICTIONARY -> "icons/lucide/book-open.svg"
            ServiceType.SUMMARIZER -> "icons/lucide/text-align-start.svg"
            ServiceType.REWRITER -> "icons/lucide/pen-line.svg"
            // No service declares this yet; the generic icon is a placeholder until one does.
            ServiceType.IMAGE_SEARCH -> "icons/lucide/search.svg"
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
                if (model != null) {
                    DynamicPluginSettingsDialog(
                        owner               = SwingUtilities.getWindowAncestor(this@PluginsPanel),
                        pluginName          = plugin.manifest.name,
                        localizationManager = localizationManager,
                        settingsModel       = model,
                        settingsInstance    = instance,
                        onSave = { map ->
                            scope.launch { pluginManager.applySettingsFromMap(plugin.manifest.id, map) }
                        }
                    ).isVisible = true
                } else {
                    JOptionPane.showMessageDialog(this@PluginsPanel,
                        localizationManager.getString("settings_plugins.no_settings_msg"),
                        plugin.manifest.name, JOptionPane.INFORMATION_MESSAGE)
                }
            }
        }
    }

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
        /** Aligns a plugin's version line under its name rather than under its icon. */
        const val NAME_INDENT = 32
    }
}

// ── WrapLayout ────────────────────────────────────────────────────────────────

/**
 * A [FlowLayout] subclass that correctly reports [preferredLayoutSize] when items
 * wrap to multiple rows.
 *
 * Standard [FlowLayout.preferredLayoutSize] always returns a single-row height,
 * so its containing [GridBagLayout] row never grows tall enough to show wrapped
 * items — they simply disappear below the allocated space.
 *
 * This class recalculates preferred height by simulating the actual row breaks
 * for the current container width, matching what the layout engine will actually
 * produce at paint time.
 */
private class WrapLayout(
    align: Int = LEADING,
    hgap: Int  = 5,
    vgap: Int  = 5
) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension =
        computeSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension =
        computeSize(target, preferred = false).also { it.width -= (hgap + 1) }

    private fun computeSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            // Use the actual current width if available; fall back to "infinite"
            // on the very first pass (before the component has been sized).
            val containerWidth = target.size.width.takeIf { it > 0 } ?: Int.MAX_VALUE
            val insets = target.insets
            val horizontalInsets = insets.left + insets.right + hgap * 2
            val maxRowWidth = containerWidth - horizontalInsets

            var rowWidth  = 0
            var rowHeight = 0
            var totalWidth  = 0
            var totalHeight = insets.top + insets.bottom + vgap * 2

            for (i in 0 until target.componentCount) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                // Would this component exceed the row limit?
                if (rowWidth > 0 && rowWidth + hgap + d.width > maxRowWidth) {
                    totalWidth   = maxOf(totalWidth, rowWidth)
                    totalHeight += rowHeight + vgap
                    rowWidth  = 0
                    rowHeight = 0
                }
                if (rowWidth > 0) rowWidth += hgap
                rowWidth  += d.width
                rowHeight  = maxOf(rowHeight, d.height)
            }
            // Flush the last row
            totalWidth   = maxOf(totalWidth, rowWidth)
            totalHeight += rowHeight

            return Dimension(totalWidth + horizontalInsets, totalHeight)
        }
    }
}

// ── Extension ─────────────────────────────────────────────────────────────────

fun ServiceType.readableName(localizationManager: LocalizationManager): String =
    when (this) {
        ServiceType.TRANSLATOR    -> localizationManager.getString("settings_services.translator").removeSuffix(":")
        ServiceType.TTS           -> localizationManager.getString("settings_services.tts").removeSuffix(":")
        ServiceType.OCR           -> localizationManager.getString("settings_services.ocr").removeSuffix(":")
        ServiceType.SPELL_CHECKER -> localizationManager.getString("settings_services.spell_checker").removeSuffix(":")
        ServiceType.DICTIONARY    -> localizationManager.getString("settings_services.dictionary").removeSuffix(":")
        ServiceType.SUMMARIZER    -> localizationManager.getString("settings_services.summarizer").removeSuffix(":")
        ServiceType.REWRITER      -> localizationManager.getString("settings_services.rewriter").removeSuffix(":")
        ServiceType.IMAGE_SEARCH  -> localizationManager.getString("settings_services.image_search").removeSuffix(":")
    }
