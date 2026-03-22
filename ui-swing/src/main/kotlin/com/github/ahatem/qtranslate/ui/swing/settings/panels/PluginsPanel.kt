package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.plugin.*
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.util.type
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.applyForegroundColorFilter
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.*
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Settings panel for browsing, enabling/disabling, configuring, and
 * installing/uninstalling plugins.
 *
 * Layout: left sidebar (plugin list + Install button) / right detail pane.
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

    init {
        // SettingsPanel sets BorderLayout via GridBagLayout — override for this panel
        // which needs a full-bleed split layout with no padding on the outer container.
        removeAll()
        layout = BorderLayout()
        border = BorderFactory.createEmptyBorder()

        // ---- Left: plugin list ----
        pluginList = JList(pluginListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer  = PluginCellRenderer()
            fixedCellHeight = 40
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    selectedPlugin = selectedValue
                    refreshDetails()
                }
            }
        }

        val listScroll = JScrollPane(pluginList).apply {
            border = BorderFactory.createMatteBorder(
                0, 0, 0, 1, UIManager.getColor("Component.borderColor") ?: Color.GRAY
            )
        }

        val installBtn = JButton(
            localizationManager.getString("settings_plugins.install_plugin")
        ).apply {
            addActionListener { onInstall() }
        }

        val leftPanel = JPanel(BorderLayout(0, 0)).apply {
            preferredSize = Dimension(220, 0)
            add(listScroll, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.CENTER, 8, 8)).apply {
//                isOpaque = false
                add(installBtn)
            }, BorderLayout.SOUTH)
        }

        // ---- Right: detail pane ----
        val detailScroll = JScrollPane(detailPane).apply { border = null }

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, detailScroll).apply {
            dividerLocation = 220
            dividerSize     = 1
            resizeWeight    = 0.0
            border          = null
        }

        add(split, BorderLayout.CENTER)

        // Observe live plugin list
        scope.launch {
            pluginManager.plugins.collect { plugins ->
                SwingUtilities.invokeLater {
                    val prevId = selectedPlugin?.manifest?.id
                    pluginListModel.clear()
                    plugins.forEach { pluginListModel.addElement(it) }
                    val idx = plugins.indexOfFirst { it.manifest.id == prevId }
                    pluginList.selectedIndex = if (idx >= 0) idx else if (plugins.isNotEmpty()) 0 else -1
                }
            }
        }

        showEmptyState()
    }

    // ---- Detail pane rendering ----

    private fun showEmptyState() {
        detailPane.removeAll()
        detailPane.add(JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                gridx = 0; gridy = GridBagConstraints.RELATIVE
                anchor = GridBagConstraints.CENTER
                insets = Insets(4, 0, 4, 0)
            }
            add(JLabel(iconManager.getIcon("icons/lucide/package.svg", 40, 40)), gbc)
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
//            isOpaque = false
        }
        val detail = com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag(panel, 8, 6)

        // ---- Header: icon + name + status badge ----
        val headerPanel = JPanel(BorderLayout(12, 0)).apply {
//            isOpaque = false

            val nameLabel = JLabel(plugin.manifest.name).apply {
                font = font.deriveFont(Font.BOLD, font.size + 3f)
            }
            val statusBadge = makeStatusBadge(plugin.status)

            val nameRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
//                isOpaque = false
                add(nameLabel)
                add(statusBadge)
            }

            val versionLabel = JLabel("v${plugin.manifest.version}  ·  ${plugin.manifest.author}").apply {
                foreground = UIManager.getColor("Label.disabledForeground")
            }

            add(nameRow,     BorderLayout.NORTH)
            add(versionLabel, BorderLayout.SOUTH)
        }
        detail.nextRow().spanLine().weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(0, 0, 12, 0).add(headerPanel)

        // ---- Description ----
        if (plugin.manifest.description.isNotBlank()) {
            val desc = JTextArea(plugin.manifest.description).apply {
                isEditable  = false
                lineWrap    = true
                wrapStyleWord = true
                isOpaque    = false
                border      = BorderFactory.createEmptyBorder()
                foreground  = UIManager.getColor("Label.foreground")
            }
            detail.nextRow().spanLine().weightX(1.0)
                .fill(GridBagConstraints.HORIZONTAL)
                .insets(0, 0, 12, 0).add(desc)
        }

        // ---- Services ----
        if (plugin.services.isNotEmpty()) {
            detail.nextRow().spanLine().add(
                makeLabel(localizationManager.getString("settings_plugins.provided_services"))
            )

            val servicePanel = JPanel(GridLayout(0, 1, 0, 2)).apply { }
            plugin.services.forEach { service ->
                servicePanel.add(JLabel("  •  ${service.name}  (${service.type?.readableName(localizationManager)})").apply {
                    foreground = UIManager.getColor("Label.disabledForeground")
                })
            }
            detail.nextRow().spanLine().weightX(1.0)
                .fill(GridBagConstraints.HORIZONTAL)
                .insets(4, 0, 12, 0).add(servicePanel)
        }

        // ---- Error ----
        if (plugin.status == PluginStatus.FAILED && plugin.lastError != null) {
            detail.nextRow().spanLine().add(
                makeLabel(
                    localizationManager.getString("settings_plugins.plugin_error_label"),
                    UIManager.getColor("Actions.Red")
                )
            )

            val errorArea = JTextArea(plugin.lastError!!.message ?: "Unknown error").apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                isOpaque = false
                foreground = UIManager.getColor("Actions.Red") ?: Color.RED
                border = BorderFactory.createEmptyBorder()
            }
            detail.nextRow().spanLine().weightX(1.0)
                .fill(GridBagConstraints.HORIZONTAL)
                .insets(4, 0, 12, 0).add(errorArea)
        }

        // ---- Action buttons ----
        detail.nextRow().spanLine()
            .fill(GridBagConstraints.NONE)
            .anchor(GridBagConstraints.WEST)
            .insets(4, 0, 0, 0).add(buildActionButtons(plugin))

        // glue
        detail.nextRow().spanLine().weightX(1.0).weightY(1.0).fill(GridBagConstraints.BOTH)
            .add(Box.createVerticalGlue())

        detailPane.removeAll()
        detailPane.add(panel, BorderLayout.CENTER)
        detailPane.revalidate()
        detailPane.repaint()
    }

    private fun makeLabel(text: String, color: Color? = null) = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
        if (color != null) foreground = color
    }

    private fun makeStatusBadge(status: PluginStatus): JLabel {
        val (text, bg, fg) = when (status) {
            PluginStatus.ENABLED -> Triple(
                localizationManager.getString("settings_plugins.status_enabled"),
                Color(0x2E7D32),
                Color.WHITE
            )
            PluginStatus.DISABLED -> Triple(
                localizationManager.getString("settings_plugins.status_disabled"),
                Color(0x757575),
                Color.WHITE
            )
            PluginStatus.FAILED -> Triple(
                localizationManager.getString("settings_plugins.status_failed"),
                Color(0xC62828),
                Color.WHITE
            )
            PluginStatus.AWAITING_VERIFICATION -> Triple(
                localizationManager.getString("settings_plugins.status_verification"),
                Color(0xE65100),
                Color.WHITE
            )
        }

        return JLabel(" $text ").apply {
            foreground = fg
            background = bg
            isOpaque = true
            font = font.deriveFont(Font.BOLD, font.size - 1f)
            border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            putClientProperty("FlatLaf.style", "arc: 8")
        }
    }

    private fun buildActionButtons(plugin: PluginState): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
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

    // ---- Actions ----

    private fun onInstall() {
        val chooser = JFileChooser().apply {
            dialogTitle = localizationManager.getString("settings_plugins.install_dialog_title")
            fileFilter = FileNameExtensionFilter(
                localizationManager.getString("settings_plugins.install_dialog_filter"),
                "jar"
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
        val message = localizationManager
            .getString("settings_plugins.uninstall_confirm_msg")
            .format(plugin.manifest.name)

        val result = JOptionPane.showConfirmDialog(
            this,
            message,
            localizationManager.getString("settings_plugins.uninstall_confirm_title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (result == JOptionPane.YES_OPTION) {
            scope.launch { pluginManager.uninstallPlugin(plugin.manifest.id) }
        }
    }

    private fun onConfigure(plugin: PluginState) {
        scope.launch {
            val model = pluginManager.getPluginSettingsModel(plugin.manifest.id)
            SwingUtilities.invokeLater {
                if (model != null) {
                    DynamicPluginSettingsDialog(
                        owner = SwingUtilities.getWindowAncestor(this@PluginsPanel),
                        pluginName = plugin.manifest.name,
                        localizationManager = localizationManager,
                        settingsModel = model,
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

    // SettingsPanel.render() is not used — plugin list is driven by pluginManager.plugins flow
    override fun render(state: SettingsState) = Unit

    // ---- Cell renderer ----

    /**
     * Shows each plugin's own icon (from [PluginManifest.icon] via the plugin's
     * class loader) with a small status overlay badge in the bottom-right corner.
     *
     * Falls back to a generic "package" placeholder when the plugin has no icon.
     *
     * Icons are cached inside [IconManager] — calling getIcon() here on every
     * render is safe and won't recreate the FlatSVGIcon repeatedly.
     */
    private inner class PluginCellRenderer : DefaultListCellRenderer() {

        // Status overlay icons — small, pre-loaded, color-filtered to match theme
        private val statusIcons: Map<PluginStatus, Icon> = mapOf(
            PluginStatus.ENABLED               to iconManager.getIcon("icons/lucide/check-circle.svg",  12, 12),
            PluginStatus.DISABLED              to iconManager.getIcon("icons/lucide/circle.svg",         12, 12),
            PluginStatus.FAILED                to iconManager.getIcon("icons/lucide/x-circle.svg",       12, 12),
            PluginStatus.AWAITING_VERIFICATION to iconManager.getIcon("icons/lucide/alert-circle.svg",   12, 12)
        ).mapValues { (_, icon) ->
            (icon as? com.formdev.flatlaf.extras.FlatSVGIcon)
                ?.applyForegroundColorFilter()
                ?: icon
        }

        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is PluginState) {
                text   = value.manifest.name
                border = BorderFactory.createEmptyBorder(6, 10, 6, 10)

                // Load the plugin's own icon using the plugin classloader so the
                // resource is resolved inside the plugin JAR, not the app classpath.
                // The first service ID is used to look up the classloader — all
                // services in one plugin share the same JAR and classloader.
                val pluginIconPath = value.manifest.icon
                val serviceId      = value.services.firstOrNull()?.id

                icon = if (pluginIconPath != null && serviceId != null) {
                    iconManager.getIcon(serviceId, pluginIconPath, 16, 16)
                } else {
                    // No icon declared in plugin.json — use generic placeholder
                    iconManager.getIcon("icons/lucide/package.svg", 16, 16)
                }
            }
            return this
        }
    }
}

fun ServiceType.readableName(localizationManager: LocalizationManager): String =
    when (this) {
        ServiceType.TRANSLATOR ->
            localizationManager.getString("settings_services.translator").removeSuffix(":")

        ServiceType.TTS ->
            localizationManager.getString("settings_services.tts").removeSuffix(":")

        ServiceType.OCR ->
            localizationManager.getString("settings_services.ocr").removeSuffix(":")

        ServiceType.SPELL_CHECKER ->
            localizationManager.getString("settings_services.spell_checker").removeSuffix(":")

        ServiceType.DICTIONARY ->
            localizationManager.getString("settings_services.dictionary").removeSuffix(":")
    }