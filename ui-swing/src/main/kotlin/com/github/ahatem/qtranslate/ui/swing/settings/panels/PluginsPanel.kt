package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.*
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.util.type
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag
import com.github.ahatem.qtranslate.ui.swing.shared.util.applyForegroundColorFilter
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
 * Layout: master-detail split.
 * - **Left**: scrollable list of plugins — two-line cells showing name (bold) and
 *   colored status text below. Install and Browse buttons at the bottom.
 * - **Right**: detail pane showing the selected plugin's full metadata, services,
 *   error log (if any), and action buttons (Enable / Disable / Configure / Uninstall).
 *
 * The panel overrides SettingsPanel's default GridBag layout to use [BorderLayout]
 * for the master-detail split. [buildSeparatorRow] (inherited as `protected` from
 * [SettingsPanel]) is reused to draw consistent section headers inside the detail pane.
 */
class PluginsPanel(
    private val iconManager: IconManager,
    private val pluginManager: PluginManager,
    private val localizationManager: LocalizationManager,
    private val scope: CoroutineScope
) : SettingsPanel() {

    private val pluginListModel = DefaultListModel<PluginState>()
    private val pluginList: JList<PluginState>
    private val detailPane = JPanel(BorderLayout())
    private var selectedPlugin: PluginState? = null

    // Kept as a field so the UIManager listener can refresh its right-edge divider.
    private val leftPanel: JPanel

    init {
        // Override the SettingsPanel default (GridBag + padding) with a plain BorderLayout
        removeAll()
        layout = BorderLayout()
        border = BorderFactory.createEmptyBorder()

        // ── Left: plugin list ─────────────────────────────────────────────────
        pluginList = JList(pluginListModel).apply {
            selectionMode   = ListSelectionModel.SINGLE_SELECTION
            cellRenderer    = PluginCellRenderer()
            fixedCellHeight = 46          // tall enough for two lines + vertical padding
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    selectedPlugin = selectedValue
                    refreshDetails()
                }
            }
        }

        val listScroll = JScrollPane(pluginList).apply {
            border = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }

        val installBtn = JButton(
            localizationManager.getString("settings_plugins.install_plugin")
        ).apply { addActionListener { onInstall() } }

        val browseLink = JLabel(
            "<html><u>${localizationManager.getString("settings_plugins.browse_on_github")}</u></html>"
        ).apply {
            cursor      = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground  = UIManager.getColor("Component.accentColor")
                ?: UIManager.getColor("Label.foreground")
            font        = font.deriveFont(font.size - 1f)
            toolTipText = "https://github.com/topics/qtranslate-plugin"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) =
                    runCatching {
                        Desktop.getDesktop().browse(URI("https://github.com/topics/qtranslate-plugin"))
                    }.let {}
            })
        }

        val bottomBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(6, 8, 8, 8)
            add(installBtn, BorderLayout.LINE_START)
            add(browseLink, BorderLayout.LINE_END)
        }

        leftPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(200, 0)
            minimumSize   = Dimension(200, 0)
            maximumSize   = Dimension(200, Int.MAX_VALUE)
            add(listScroll, BorderLayout.CENTER)
            add(bottomBar,  BorderLayout.SOUTH)
        }

        // Right-edge divider — refreshed on theme change
        applyLeftPanelBorder()
        UIManager.addPropertyChangeListener { evt ->
            if (evt.propertyName == "lookAndFeel")
                SwingUtilities.invokeLater { applyLeftPanelBorder() }
        }

        // ── Right: scrollable detail pane ─────────────────────────────────────
        val detailScroll = JScrollPane(detailPane).apply {
            border = null
            verticalScrollBar.unitIncrement = 16
        }

        val mainPanel = JPanel(BorderLayout()).apply {
            add(leftPanel,    BorderLayout.LINE_START)
            add(detailScroll, BorderLayout.CENTER)
        }

        add(mainPanel, BorderLayout.CENTER)

        // Observe live plugin list from PluginManager
        scope.launch {
            pluginManager.plugins.collect { plugins ->
                SwingUtilities.invokeLater {
                    val prevId = selectedPlugin?.manifest?.id
                    pluginListModel.clear()
                    plugins.forEach { pluginListModel.addElement(it) }
                    val idx = plugins.indexOfFirst { it.manifest.id == prevId }
                    pluginList.selectedIndex = when {
                        idx >= 0          -> idx
                        plugins.isNotEmpty() -> 0
                        else              -> -1
                    }
                }
            }
        }

        showEmptyState()
    }

    private fun applyLeftPanelBorder() {
        leftPanel.border = MatteBorder(0, 0, 0, 1,
            UIManager.getColor("Component.borderColor") ?: Color.GRAY)
        leftPanel.revalidate()
    }

    // ── Detail pane ───────────────────────────────────────────────────────────

    private fun showEmptyState() {
        detailPane.removeAll()
        detailPane.add(JPanel(GridBagLayout()).apply {
            isOpaque = false
            val gbc = GridBagConstraints().apply {
                gridx = 0; gridy = GridBagConstraints.RELATIVE
                anchor = GridBagConstraints.CENTER
                insets = Insets(6, 0, 6, 0)
            }

            val pkgIcon = runCatching {
                val icon = FlatSVGIcon("icons/lucide/package.svg", 40, 40, javaClass.classLoader)
                icon.colorFilter = FlatSVGIcon.ColorFilter {
                    UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                }
                icon as Icon
            }.getOrNull()
            if (pkgIcon != null) add(JLabel(pkgIcon), gbc)

            add(JLabel(localizationManager.getString("settings_plugins.empty_selection_hint")).apply {
                foreground = UIManager.getColor("Label.disabledForeground")
            }, gbc)
        }, BorderLayout.CENTER)
        detailPane.revalidate()
        detailPane.repaint()
    }

    private fun refreshDetails() {
        val plugin = selectedPlugin ?: run { showEmptyState(); return }

        val panel = JPanel().apply {
            layout = GridBagLayout()
            border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
        }
        val detail = GridBag(panel, 8, 8)

        // ── Name + version row ────────────────────────────────────────────────
        val nameLabel = JLabel(plugin.manifest.name).apply {
            font = font.deriveFont(Font.BOLD, font.size + 4f)
        }
        val versionLabel = JLabel("v${plugin.manifest.version}").apply {
            foreground = UIManager.getColor("Label.disabledForeground")
            font       = font.deriveFont(font.size.toFloat())
        }
        val authorLabel = JLabel(plugin.manifest.author).apply {
            foreground = UIManager.getColor("Label.disabledForeground")
        }
        val statusBadge = makeStatusBadge(plugin.status)

        val nameRow = JPanel(FlowLayout(FlowLayout.LEADING, 8, 0)).apply {
            isOpaque = false
            add(nameLabel)
            add(statusBadge)
        }
        val metaRow = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
            isOpaque = false
            add(versionLabel)
            add(JLabel("  ·  ").apply { foreground = UIManager.getColor("Label.disabledForeground") })
            add(authorLabel)
        }
        val header = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            add(nameRow,  BorderLayout.NORTH)
            add(metaRow,  BorderLayout.SOUTH)
        }
        detail.nextRow().spanLine().weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(0, 0, 16, 0).add(header)

        // ── Description ───────────────────────────────────────────────────────
        if (plugin.manifest.description.isNotBlank()) {
            detail.nextRow().spanLine().add(
                buildSeparatorRow(localizationManager.getString("settings_plugins.section_about"), bold = true, muted = false, gap = 8)
            )
            val desc = JTextArea(plugin.manifest.description).apply {
                isEditable    = false
                lineWrap      = true
                wrapStyleWord = true
                isOpaque      = false
                border        = BorderFactory.createEmptyBorder(4, 0, 0, 0)
                foreground    = UIManager.getColor("Label.foreground")
                font          = font.deriveFont(font.size.toFloat())
            }
            detail.nextRow().spanLine().weightX(1.0)
                .fill(GridBagConstraints.HORIZONTAL)
                .insets(4, 0, 14, 0).add(desc)
        }

        // ── Provided services ─────────────────────────────────────────────────
        if (plugin.services.isNotEmpty()) {
            detail.nextRow().spanLine().weightX(1.0)
                .fill(GridBagConstraints.HORIZONTAL)
                .insets(0, 0, 6, 0).add(
                    buildSeparatorRow(localizationManager.getString("settings_plugins.section_services"), bold = true, muted = false, gap = 8)
                )

            val servicePanel = JPanel().apply {
                layout   = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border   = BorderFactory.createEmptyBorder(2, 0, 0, 0)
            }
            plugin.services.forEach { service ->
                servicePanel.add(JLabel("${service.name}  ·  ${service.type?.readableName(localizationManager)}").apply {
                    foreground = UIManager.getColor("Label.disabledForeground")
                    border     = BorderFactory.createEmptyBorder(2, 4, 2, 0)
                })
            }
            detail.nextRow().spanLine().weightX(1.0)
                .fill(GridBagConstraints.HORIZONTAL)
                .insets(2, 0, 14, 0).add(servicePanel)
        }

        // ── Error ─────────────────────────────────────────────────────────────
        if (plugin.status == PluginStatus.FAILED && plugin.lastError != null) {
            val errorColor = UIManager.getColor("Actions.Red") ?: Color(0xC62828)
            detail.nextRow().spanLine().weightX(1.0)
                .fill(GridBagConstraints.HORIZONTAL)
                .insets(0, 0, 6, 0).add(
                    buildSeparatorRow(localizationManager.getString("settings_plugins.plugin_error_label"), bold = true, muted = false, gap = 8)
                        .also { (it.components.filterIsInstance<JLabel>().firstOrNull())?.foreground = errorColor }
                )
            val errorArea = JTextArea(plugin.lastError!!.message).apply {
                isEditable    = false
                lineWrap      = true
                wrapStyleWord = true
                isOpaque      = false
                foreground    = errorColor
                border        = BorderFactory.createEmptyBorder(4, 4, 0, 0)
            }
            detail.nextRow().spanLine().weightX(1.0)
                .fill(GridBagConstraints.HORIZONTAL)
                .insets(2, 0, 14, 0).add(errorArea)
        }

        // ── Action buttons ────────────────────────────────────────────────────
        detail.nextRow().spanLine()
            .fill(GridBagConstraints.NONE)
            .anchor(GridBagConstraints.LINE_START)
            .insets(4, 0, 0, 0).add(buildActionButtons(plugin))

        // Push content to top
        detail.nextRow().spanLine().weightX(1.0).weightY(1.0)
            .fill(GridBagConstraints.BOTH).add(Box.createVerticalGlue())

        detailPane.removeAll()
        detailPane.add(panel, BorderLayout.CENTER)
        detailPane.revalidate()
        detailPane.repaint()
    }

    private fun makeStatusBadge(status: PluginStatus): JLabel {
        val (text, bg) = when (status) {
            PluginStatus.ENABLED               ->
                localizationManager.getString("settings_plugins.status_enabled")   to Color(0x2E7D32)
            PluginStatus.DISABLED              ->
                localizationManager.getString("settings_plugins.status_disabled")  to Color(0x757575)
            PluginStatus.FAILED                ->
                localizationManager.getString("settings_plugins.status_failed")    to Color(0xC62828)
            PluginStatus.AWAITING_VERIFICATION ->
                localizationManager.getString("settings_plugins.status_verification") to Color(0xE65100)
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

    private fun buildActionButtons(plugin: PluginState): JPanel =
        JPanel(FlowLayout(FlowLayout.LEADING, 6, 0)).apply {
            isOpaque = false
            when (plugin.status) {
                PluginStatus.ENABLED -> {
                    add(JButton(localizationManager.getString("settings_plugins.btn_disable")).apply {
                        addActionListener { scope.launch { pluginManager.disablePlugin(plugin.manifest.id) } }
                    })
                    add(JButton(localizationManager.getString("settings_plugins.btn_configure")).apply {
                        addActionListener { onConfigure(plugin) }
                    })
                }
                PluginStatus.DISABLED -> {
                    add(JButton(localizationManager.getString("settings_plugins.btn_enable")).apply {
                        addActionListener { scope.launch { pluginManager.enablePlugin(plugin.manifest.id) } }
                    })
                }
                PluginStatus.AWAITING_VERIFICATION -> {
                    add(JButton(localizationManager.getString("settings_plugins.btn_accept_update")).apply {
                        toolTipText = localizationManager.getString("settings_plugins.tip_accept_update")
                        addActionListener { scope.launch { pluginManager.resolveAsUpdate(plugin.manifest.id) } }
                    })
                    add(JButton(localizationManager.getString("settings_plugins.btn_clean_install")).apply {
                        toolTipText = localizationManager.getString("settings_plugins.tip_clean_install")
                        addActionListener { scope.launch { pluginManager.resolveAsCleanInstall(plugin.manifest.id) } }
                    })
                }
                PluginStatus.FAILED -> Unit
            }
            add(JButton(localizationManager.getString("settings_plugins.btn_uninstall")).apply {
                foreground = UIManager.getColor("Actions.Red") ?: Color.RED
                addActionListener { onUninstall(plugin) }
            })
        }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun onInstall() {
        val chooser = JFileChooser().apply {
            dialogTitle = localizationManager.getString("settings_plugins.install_dialog_title")
            fileFilter  = FileNameExtensionFilter(
                localizationManager.getString("settings_plugins.install_dialog_filter"), "jar"
            )
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        scope.launch {
            pluginManager.installPlugin(chooser.selectedFile).fold(
                success = {
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(
                            this@PluginsPanel,
                            localizationManager.getString("settings_plugins.install_success_msg"),
                            localizationManager.getString("settings_plugins.install_success_title"),
                            JOptionPane.INFORMATION_MESSAGE
                        )
                    }
                },
                failure = { error ->
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(
                            this@PluginsPanel,
                            error,
                            localizationManager.getString("settings_plugins.install_fail_title"),
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                }
            )
        }
    }

    private fun onUninstall(plugin: PluginState) {
        val result = JOptionPane.showConfirmDialog(
            this,
            localizationManager.getString("settings_plugins.uninstall_confirm_msg")
                .format(plugin.manifest.name),
            localizationManager.getString("settings_plugins.uninstall_confirm_title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (result == JOptionPane.YES_OPTION)
            scope.launch { pluginManager.uninstallPlugin(plugin.manifest.id) }
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
                    JOptionPane.showMessageDialog(
                        this@PluginsPanel,
                        localizationManager.getString("settings_plugins.no_settings_msg"),
                        plugin.manifest.name,
                        JOptionPane.INFORMATION_MESSAGE
                    )
                }
            }
        }
    }

    // SettingsPanel.render() is not used — list is driven by pluginManager.plugins flow
    override fun render(state: SettingsState) = Unit

    // ── Cell renderer ─────────────────────────────────────────────────────────

    /**
     * Two-line cell: plugin name (bold, normal size) on the first line, colored
     * status text on the second (smaller, muted or status-tinted). Plugin icon
     * (from the manifest) appears to the left; falls back to a generic package icon.
     */
    private inner class PluginCellRenderer : ListCellRenderer<PluginState> {

        // Status text colors — chosen to work on both light and dark themes
        private val statusColors = mapOf(
            PluginStatus.ENABLED               to Color(0x2E7D32),
            PluginStatus.DISABLED              to null,                // use disabledForeground
            PluginStatus.FAILED                to Color(0xC62828),
            PluginStatus.AWAITING_VERIFICATION to Color(0xE65100)
        )

        private val statusLabels = mapOf(
            PluginStatus.ENABLED               to "settings_plugins.status_enabled",
            PluginStatus.DISABLED              to "settings_plugins.status_disabled",
            PluginStatus.FAILED                to "settings_plugins.status_failed",
            PluginStatus.AWAITING_VERIFICATION to "settings_plugins.status_verification"
        )

        override fun getListCellRendererComponent(
            list: JList<out PluginState>, value: PluginState?,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val plugin = value ?: return JPanel()

            // ── Icon ──────────────────────────────────────────────────────────
            val pluginIconPath = plugin.manifest.icon
            val serviceId      = plugin.services.firstOrNull()?.id
            val icon: Icon = if (pluginIconPath != null && serviceId != null) {
                iconManager.getIcon(serviceId, pluginIconPath, 16, 16)
            } else {
                iconManager.getIcon("icons/lucide/package.svg", 16, 16)
            }

            // ── Name line ─────────────────────────────────────────────────────
            val nameLabel = JLabel(plugin.manifest.name, icon, SwingConstants.LEADING).apply {
                font        = font.deriveFont(Font.BOLD)
                iconTextGap = 8
            }

            // ── Status line ───────────────────────────────────────────────────
            val statusKey  = statusLabels[plugin.status] ?: "settings_plugins.status_disabled"
            val statusText = localizationManager.getString(statusKey)
            val statusColor = statusColors[plugin.status]
            val statusLabel = JLabel("  $statusText").apply {
                font       = font.deriveFont(font.size - 1f)
                foreground = statusColor
                    ?: UIManager.getColor("Label.disabledForeground")
                    ?: Color.GRAY
            }

            // ── Assemble ──────────────────────────────────────────────────────
            val bg = if (isSelected)
                UIManager.getColor("List.selectionBackground")
            else
                UIManager.getColor("List.background") ?: list.background

            return JPanel().apply {
                layout   = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = true
                background = bg
                border   = BorderFactory.createEmptyBorder(6, 10, 6, 10)
                add(nameLabel)
                add(Box.createVerticalStrut(2))
                add(statusLabel)

                nameLabel.foreground   = if (isSelected)
                    UIManager.getColor("List.selectionForeground") ?: Color.WHITE
                else
                    UIManager.getColor("Label.foreground") ?: list.foreground
            }
        }
    }
}

fun ServiceType.readableName(localizationManager: LocalizationManager): String =
    when (this) {
        ServiceType.TRANSLATOR   -> localizationManager.getString("settings_services.translator").removeSuffix(":")
        ServiceType.TTS          -> localizationManager.getString("settings_services.tts").removeSuffix(":")
        ServiceType.OCR          -> localizationManager.getString("settings_services.ocr").removeSuffix(":")
        ServiceType.SPELL_CHECKER -> localizationManager.getString("settings_services.spell_checker").removeSuffix(":")
        ServiceType.DICTIONARY   -> localizationManager.getString("settings_services.dictionary").removeSuffix(":")
        ServiceType.SUMMARIZER   -> localizationManager.getString("settings_services.summarizer").removeSuffix(":")
        ServiceType.REWRITER     -> localizationManager.getString("settings_services.rewriter").removeSuffix(":")
    }
