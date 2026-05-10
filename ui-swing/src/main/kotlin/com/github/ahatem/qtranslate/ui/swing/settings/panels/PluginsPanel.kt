package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.*
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.util.type
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.*
import javax.swing.border.MatteBorder
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
    private val pluginListModel = DefaultListModel<PluginState>()
    private val pluginList: JList<PluginState>
    private val leftPanel: JPanel          // kept for theme-refresh of right border
    private val detailScroll: JScrollPane  // scrollable detail area
    private val actionBar = JPanel()       // fixed footer — never scrolls
    private var selectedPlugin: PluginState? = null

    // ── Status dot colors ─────────────────────────────────────────────────────
    private val dotEnabled  = Color(0x4CAF50)
    private val dotDisabled = Color(0x9E9E9E)
    private val dotFailed   = Color(0xE53935)
    private val dotPending  = Color(0xFF9800)

    init {
        // PluginsPanel overrides the SettingsPanel GridBag layout
        removeAll()
        layout = BorderLayout()
        border = BorderFactory.createEmptyBorder()

        // ── Left: plugin list ─────────────────────────────────────────────────
        pluginList = JList(pluginListModel).apply {
            selectionMode   = ListSelectionModel.SINGLE_SELECTION
            cellRenderer    = PluginCellRenderer()
            fixedCellHeight = 36
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    selectedPlugin = selectedValue
                    rebuildDetail()
                }
            }
        }

        val listScroll = JScrollPane(pluginList).apply {
            border = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }

        val installBtn = JButton(localizationManager.getString("settings_plugins.install_plugin"))
            .apply { addActionListener { onInstall() } }

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

        val leftBottom = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(8, 8, 10, 8)
            add(installBtn, BorderLayout.NORTH)
            add(browseLink, BorderLayout.SOUTH)
        }

        leftPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(200, 0)
            minimumSize   = Dimension(180, 0)
            maximumSize   = Dimension(200, Int.MAX_VALUE)
            add(listScroll, BorderLayout.CENTER)
            add(leftBottom, BorderLayout.SOUTH)
        }
        refreshLeftBorder()
        UIManager.addPropertyChangeListener { evt ->
            if (evt.propertyName == "lookAndFeel") SwingUtilities.invokeLater { refreshLeftBorder() }
        }

        // ── Right: detail scroll + fixed action bar ───────────────────────────
        // Placeholder empty content — replaced by rebuildDetail()
        detailScroll = JScrollPane().apply {
            border = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }

        val rightPanel = JPanel(BorderLayout()).apply {
            add(detailScroll, BorderLayout.CENTER)
            add(actionBar,    BorderLayout.SOUTH)
        }

        add(leftPanel,  BorderLayout.LINE_START)
        add(rightPanel, BorderLayout.CENTER)

        // Observe live plugin list
        scope.launch {
            pluginManager.plugins.collect { plugins ->
                SwingUtilities.invokeLater {
                    val prevId = selectedPlugin?.manifest?.id
                    pluginListModel.clear()
                    plugins.forEach { pluginListModel.addElement(it) }
                    val idx = plugins.indexOfFirst { it.manifest.id == prevId }
                    pluginList.selectedIndex = when {
                        idx >= 0             -> idx
                        plugins.isNotEmpty() -> 0
                        else                 -> -1
                    }
                }
            }
        }

        rebuildDetail()
    }

    // ── Theme helpers ─────────────────────────────────────────────────────────

    private fun refreshLeftBorder() {
        leftPanel.border = MatteBorder(0, 0, 0, 1, UIManager.getColor("Component.borderColor") ?: Color.GRAY)
        leftPanel.revalidate()
    }

    private fun refreshActionBarBorder() {
        actionBar.border = BorderFactory.createCompoundBorder(
            MatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor") ?: Color.GRAY),
            BorderFactory.createEmptyBorder(10, 16, 12, 16)
        )
    }

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
        val serviceId      = plugin.services.firstOrNull()?.id
        val headerIcon: Icon = if (pluginIconPath != null && serviceId != null)
            iconManager.getIcon(serviceId, pluginIconPath, 24, 24)
        else
            FlatSVGIcon("icons/lucide/package.svg", 24, 24, javaClass.classLoader).apply {
                colorFilter = FlatSVGIcon.ColorFilter { UIManager.getColor("Label.foreground") }
            }

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

            // WrapLayout correctly reports multi-row preferred height, so GridBagLayout
            // allocates the right vertical space when chips wrap to a second line.
            val chips = JPanel(WrapLayout(FlowLayout.LEADING, 6, 4)).apply {
                isOpaque = false
                plugin.services.forEach { svc ->
                    add(buildServiceChip(svc.name, svc.type?.readableName(localizationManager)))
                }
            }
            g.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
                .insets(0, 0, 0, 0).add(chips)
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
            add(JLabel(localizationManager.getString("settings_plugins.empty_selection_hint")).apply {
                foreground = UIManager.getColor("Label.disabledForeground")
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

        // Keep the bar's border fresh across theme switches
        UIManager.addPropertyChangeListener { evt ->
            if (evt.propertyName == "lookAndFeel") SwingUtilities.invokeLater { refreshActionBarBorder() }
        }

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

    private fun buildServiceChip(name: String, typeName: String?): JLabel {
        val text = if (typeName != null && typeName != name) "$name ($typeName)" else name
        return JLabel(text).apply {
            font   = font.deriveFont(font.size - 1f)
            border = BorderFactory.createCompoundBorder(
                themeAwareBorder(),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)
            )
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun onInstall() {
        val chooser = JFileChooser().apply {
            dialogTitle = localizationManager.getString("settings_plugins.install_dialog_title")
            fileFilter  = FileNameExtensionFilter(
                localizationManager.getString("settings_plugins.install_dialog_filter"), "jar")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        scope.launch {
            pluginManager.installPlugin(chooser.selectedFile).fold(
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

    // ── Cell renderer ─────────────────────────────────────────────────────────

    /**
     * Single-line cell: plugin icon (15 px) + bold name + right-aligned status dot (8 px).
     * The dot is a filled circle — green/gray/red/orange — so status is instantly readable
     * without any text taking up horizontal space.
     */
    private inner class PluginCellRenderer : ListCellRenderer<PluginState> {

        private inner class StatusDot(val dotColor: Color) : JComponent() {
            init { preferredSize = Dimension(8, 8); minimumSize = preferredSize; isOpaque = false }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = dotColor
                g2.fillOval(0, 0, width, height)
            }
        }

        override fun getListCellRendererComponent(
            list: JList<out PluginState>, value: PluginState?,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val plugin = value ?: return JPanel()

            val bg = if (isSelected) UIManager.getColor("List.selectionBackground") else UIManager.getColor("List.background") ?: list.background
            val fg = if (isSelected) UIManager.getColor("List.selectionForeground") ?: Color.WHITE else UIManager.getColor("Label.foreground") ?: list.foreground

            val icon: Icon = plugin.manifest.icon?.let { path ->
                plugin.services.firstOrNull()?.id?.let { svc ->
                    iconManager.getIcon(svc, path, 15, 15)
                }
            } ?: FlatSVGIcon("icons/lucide/package.svg", 15, 15, javaClass.classLoader).apply {
                colorFilter = FlatSVGIcon.ColorFilter { UIManager.getColor("Label.foreground") }
            }

            val nameLabel = JLabel(plugin.manifest.name, icon, SwingConstants.LEADING).apply {
                font        = font.deriveFont(Font.BOLD)
                foreground  = fg
                iconTextGap = 7
            }

            val dotColor = when (plugin.status) {
                PluginStatus.ENABLED               -> dotEnabled
                PluginStatus.DISABLED              -> dotDisabled
                PluginStatus.FAILED                -> dotFailed
                PluginStatus.AWAITING_VERIFICATION -> dotPending
            }

            // Dot is centered vertically in a fixed-width wrapper so it doesn't affect
            // the cell's preferred height or cause the name label to be misaligned.
            val dotWrapper = JPanel(GridBagLayout()).apply {
                isOpaque = false
                preferredSize = Dimension(18, 0)
                add(StatusDot(dotColor), GridBagConstraints().apply { anchor = GridBagConstraints.CENTER })
            }

            return JPanel(BorderLayout()).apply {
                isOpaque   = true
                background = bg
                border     = BorderFactory.createEmptyBorder(0, 10, 0, 10)
                add(nameLabel,  BorderLayout.CENTER)
                add(dotWrapper, BorderLayout.LINE_END)
            }
        }
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
    }
