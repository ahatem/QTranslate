package com.github.ahatem.qtranslate.ui.swing.settings

import com.formdev.flatlaf.extras.FlatSVGIcon
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
) : JDialog(owner, "Settings", true) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Nav items (ordered) ───────────────────────────────────────────────────
    private val navItems = listOf(
        localizationManager.getString("settings_dialog_sidebar.general"),
        localizationManager.getString("settings_dialog_sidebar.appearance"),
        localizationManager.getString("settings_dialog_sidebar.services"),
        localizationManager.getString("settings_dialog_sidebar.plugins"),
        localizationManager.getString("settings_dialog_sidebar.hotkeys"),
        localizationManager.getString("settings_dialog_sidebar.translation"),
        localizationManager.getString("settings_dialog_sidebar.languages"),
        localizationManager.getString("settings_dialog_sidebar.window_layout")
    )

    /** SVG resource paths keyed by localized nav label. */
    // @formatter:off
    private val sidebarIconPaths: Map<String, String> = mapOf(
        localizationManager.getString("settings_dialog_sidebar.general")       to "icons/lucide/sliders-horizontal.svg",
        localizationManager.getString("settings_dialog_sidebar.appearance")    to "icons/lucide/palette.svg",
        localizationManager.getString("settings_dialog_sidebar.services")      to "icons/lucide/zap.svg",
        localizationManager.getString("settings_dialog_sidebar.plugins")       to "icons/lucide/package.svg",
        localizationManager.getString("settings_dialog_sidebar.hotkeys")       to "icons/lucide/keyboard.svg",
        localizationManager.getString("settings_dialog_sidebar.translation")   to "icons/lucide/languages.svg",
        localizationManager.getString("settings_dialog_sidebar.languages")     to "icons/lucide/globe.svg",
        localizationManager.getString("settings_dialog_sidebar.window_layout") to "icons/lucide/layout-dashboard.svg"
    )
    // @formatter:on

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

    private val contentArea = JPanel(BorderLayout())

    private val panelTitle = JLabel(navItems.firstOrNull() ?: "").apply {
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

        // Apply borders based on current theme, then keep them fresh on theme changes
        updateBorders()
        UIManager.addPropertyChangeListener { evt ->
            if (evt.propertyName == "lookAndFeel") SwingUtilities.invokeLater { updateBorders() }
        }

        rootPane.registerKeyboardAction(
            { cancelAndClose() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = cancelAndClose()
        })

        observeState()

        minimumSize = Dimension(860, 580)
        preferredSize = Dimension(1020, 700)
        pack()
        setLocationRelativeTo(owner)

        tree.setSelectionRow(0)
    }

    // ── Theme-aware borders ───────────────────────────────────────────────────

    private fun updateBorders() {
        val bc = UIManager.getColor("Component.borderColor") ?: Color.GRAY

        sidebarPanel.border = MatteBorder(0, 0, 0, 1, bc)

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
        navItems.forEach { root.add(DefaultMutableTreeNode(it)) }

        return JTree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            showsRootHandles = false
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

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
                    border = BorderFactory.createEmptyBorder(0, 8, 0, 8)

                    if (!sel) foreground = UIManager.getColor("Label.foreground")
                    return this
                }
            }

            addTreeSelectionListener { e ->
                val name = (e.path.lastPathComponent as? DefaultMutableTreeNode)
                    ?.userObject as? String ?: return@addTreeSelectionListener
                showPanel(name)
            }
        }
    }

    private fun buildSidebar(): JPanel {
        val treeScroll = JScrollPane(tree).apply {
            border = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        return JPanel(BorderLayout()).apply {
            minimumSize = Dimension(160, 0)
            // Let preferred width be driven by the tree's widest row
            add(treeScroll, BorderLayout.CENTER)
        }
    }

    // ── Panel management ──────────────────────────────────────────────────────

    private fun showPanel(name: String) {
        currentPanelName = name
        panelTitle.text = name

        val panel = panelCache.getOrPut(name) { createPanel(name) }

        contentArea.components.filterIsInstance<JScrollPane>().forEach { contentArea.remove(it) }
        contentArea.add(JScrollPane(panel).apply {
            border = null
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

    private fun createPanel(name: String): JPanel = when (name) {
        localizationManager.getString("settings_dialog_sidebar.general") ->
            GeneralPanel(settingsStore, localizationManager, availableLanguages)

        localizationManager.getString("settings_dialog_sidebar.appearance") ->
            AppearancePanel(settingsStore, themeManager, localizationManager, scope)

        localizationManager.getString("settings_dialog_sidebar.services") ->
            ServicesPanel(settingsStore, pluginManager, localizationManager, scope)

        localizationManager.getString("settings_dialog_sidebar.plugins") ->
            PluginsPanel(iconManager, pluginManager, localizationManager, scope)

        localizationManager.getString("settings_dialog_sidebar.hotkeys") ->
            KeyboardPanel(settingsStore, localizationManager, pauseGlobalHotkeys, resumeGlobalHotkeys)

        localizationManager.getString("settings_dialog_sidebar.translation") ->
            TranslationPanel(settingsStore, localizationManager)

        localizationManager.getString("settings_dialog_sidebar.languages") ->
            LanguagesPanel(settingsStore, localizationManager)

        localizationManager.getString("settings_dialog_sidebar.window_layout") ->
            WindowPanel(settingsStore, localizationManager)

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
}
