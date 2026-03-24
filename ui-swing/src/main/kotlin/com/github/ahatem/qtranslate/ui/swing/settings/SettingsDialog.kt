package com.github.ahatem.qtranslate.ui.swing.settings

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
    private val localizationManager: LocalizationManager
) : JDialog(owner, "Settings", true) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val navItems = listOf(
        localizationManager.getString("settings_dialog_sidebar.general"),
        localizationManager.getString("settings_dialog_sidebar.appearance"),
        localizationManager.getString("settings_dialog_sidebar.services"),
        localizationManager.getString("settings_dialog_sidebar.plugins"),
        localizationManager.getString("settings_dialog_sidebar.hotkeys"),
        localizationManager.getString("settings_dialog_sidebar.translation"),
        localizationManager.getString("settings_dialog_sidebar.window_layout")
    )
    private val tree: JTree

    private val contentArea = JPanel(BorderLayout())
    private val panelTitle = JLabel(
        localizationManager.getString("settings_dialog_sidebar.general")
    ).apply {
        font = font.deriveFont(Font.BOLD, font.size + 2f)
        border = BorderFactory.createEmptyBorder(0, 0, 0, 12)
    }

    private val dirtyDot = JLabel(
        localizationManager.getString("settings_dialog.unsaved_changes")
    ).apply {
        foreground = UIManager.getColor("Actions.Yellow") ?: Color(0xE65100)
        font = font.deriveFont(font.size - 1f)
        isVisible = false
    }

    private val panelCache = mutableMapOf<String, JPanel>()
    private var currentPanelName: String? = null

    private lateinit var okButton: JButton
    private lateinit var applyButton: JButton

    init {
        title = localizationManager.getString("settings_dialog.title")
        layout = BorderLayout()

        val root = DefaultMutableTreeNode("root")
        navItems.forEach { root.add(DefaultMutableTreeNode(it)) }

        tree = JTree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            showsRootHandles = false
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

            putClientProperty(
                "FlatLaf.style",
                "rowHeight: 38; selectionArc: 10; selectionInsets: 2,8,2,8; " +
                        $$"selectionBackground: $Table.selectionBackground"
            )

            cellRenderer = object : DefaultTreeCellRenderer() {
                init {
                    leafIcon = null
                    closedIcon = null
                    openIcon = null
                }

                override fun getTreeCellRendererComponent(
                    tree: JTree, value: Any, sel: Boolean,
                    expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
                ): Component {
                    super.getTreeCellRendererComponent(
                        tree, value, sel, expanded, leaf, row, hasFocus
                    )
                    border = BorderFactory.createEmptyBorder(0, 12, 0, 12)

                    if (!sel) {
                        foreground = UIManager.getColor("Label.foreground")
                    }
                    return this
                }
            }
            addTreeSelectionListener { e ->
                val name = (e.path.lastPathComponent as? DefaultMutableTreeNode)
                    ?.userObject as? String ?: return@addTreeSelectionListener
                showPanel(name)
            }
        }

        val treeScroll = JScrollPane(tree).apply {
            minimumSize = Dimension(210, 0)
            preferredSize = Dimension(210, 0)
            maximumSize = Dimension(210, Int.MAX_VALUE)
            border = BorderFactory.createMatteBorder(
                0, 0, 0, 1, UIManager.getColor("Component.borderColor") ?: Color.GRAY
            )
        }

        val headerStrip = JPanel(FlowLayout(FlowLayout.LEADING, 12, 10)).apply {
            border = BorderFactory.createMatteBorder(
                0, 0, 1, 0, UIManager.getColor("Component.borderColor") ?: Color.GRAY
            )
            add(panelTitle)
            add(dirtyDot)
        }
        contentArea.add(headerStrip, BorderLayout.NORTH)

        val mainPanel = JPanel(BorderLayout()).apply {
            add(treeScroll, BorderLayout.LINE_START)
            add(contentArea, BorderLayout.CENTER)
        }

        add(mainPanel, BorderLayout.CENTER)
        add(buildButtonBar(), BorderLayout.SOUTH)

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
        preferredSize = Dimension(1000, 680)
        pack()
        setLocationRelativeTo(owner)

        tree.setSelectionRow(0)
    }


    private fun showPanel(name: String) {
        currentPanelName = name
        panelTitle.text = name

        val panel = panelCache.getOrPut(name) { createPanel(name) }

        contentArea.components.filterIsInstance<JScrollPane>().forEach { contentArea.remove(it) }

        contentArea.add(JScrollPane(panel).apply {
            border = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
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
            GeneralPanel(settingsStore, localizationManager)

        localizationManager.getString("settings_dialog_sidebar.appearance") ->
            AppearancePanel(settingsStore, themeManager, localizationManager, scope)

        localizationManager.getString("settings_dialog_sidebar.services") ->
            ServicesPanel(settingsStore, pluginManager, localizationManager, scope)

        localizationManager.getString("settings_dialog_sidebar.plugins") ->
            PluginsPanel(iconManager, pluginManager, localizationManager, scope)

        localizationManager.getString("settings_dialog_sidebar.hotkeys") ->
            KeyboardPanel(settingsStore, localizationManager)

        localizationManager.getString("settings_dialog_sidebar.translation") ->
            TranslationPanel(settingsStore, localizationManager)

        localizationManager.getString("settings_dialog_sidebar.window_layout") ->
            WindowPanel(settingsStore, localizationManager)

        else -> JPanel()
    }

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
            border = BorderFactory.createMatteBorder(
                1, 0, 0, 0, UIManager.getColor("Component.borderColor") ?: Color.GRAY
            )
            val gbc = GridBagConstraints().apply { gridy = 0; insets = Insets(8, 8, 8, 4) }
            gbc.gridx = 0; gbc.weightx = 1.0; gbc.anchor = GridBagConstraints.LINE_START
            add(resetButton, gbc)
            gbc.gridx = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.LINE_END
            add(okButton, gbc)
            gbc.gridx = 2; add(cancelButton, gbc)
            gbc.gridx = 3; gbc.insets = Insets(8, 4, 8, 8); add(applyButton, gbc)
        }
    }

    private fun observeState() {
        scope.launch {
            settingsStore.state.collect { state ->
                withContext(Dispatchers.Swing) {
                    dirtyDot.isVisible = state.isDirty
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


    private fun onOk() {
        if (!settingsStore.state.value.isDirty) {
            dispose()
            return
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
        if (result == JOptionPane.YES_OPTION) {
            settingsStore.dispatch(SettingsIntent.ResetToDefaults)
        }
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }
}