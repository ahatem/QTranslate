package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.UIScale
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
                    border = BorderFactory.createEmptyBorder(0, 8, 0, 8)

                    // A group is a heading for the rows beneath it, so it is styled as one
                    // rather than competing with the pages it labels.
                    val isGroup = navTree.any { it is Nav.Group && it.label == name }
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
        val treeScroll = JScrollPane(tree).apply {
            clearBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        return JPanel(BorderLayout()).apply {
            minimumSize = Dimension(UIScale.scale(160), 0)
            // Let preferred width be driven by the tree's widest row
            add(treeScroll, BorderLayout.CENTER)
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

    private fun createPanel(name: String): JPanel = when (name) {
        label("general") ->
            GeneralPanel(settingsStore, localizationManager)

        label("appearance") ->
            AppearancePanel(settingsStore, themeManager, localizationManager, scope)

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
}
