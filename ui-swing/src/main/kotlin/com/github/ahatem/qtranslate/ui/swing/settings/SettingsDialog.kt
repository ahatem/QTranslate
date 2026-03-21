package com.github.ahatem.qtranslate.ui.swing.settings

import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsEvent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.settings.panels.*
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*
import javax.swing.tree.*

/**
 * The application settings dialog.
 *
 * ### Layout
 * ```
 * ┌──────────────────────────────────────────────────────────┐
 * │  [Tree nav]  │  [Panel header: title  ● Unsaved changes] │
 * │              │  ─────────────────────────────────────────│
 * │              │  [Active settings panel — scrollable]     │
 * ├──────────────────────────────────────────────────────────┤
 * │  [Reset to Defaults]          [OK]  [Cancel]  [Apply]   │
 * └──────────────────────────────────────────────────────────┘
 * ```
 *
 * ### OK behaviour
 * OK dispatches [SettingsIntent.SaveChanges] and then waits for a [SettingsEvent]
 * (success or error) before closing, so the user sees a failure if the save fails
 * instead of the dialog disappearing silently.
 */
class SettingsDialog(
    owner: JFrame,
    private val settingsStore: SettingsStore,
    private val pluginManager: PluginManager,
    private val iconManager: IconManager,
    private val themeManager: ThemeManager
) : JDialog(owner, "Settings", true) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Navigation
    private val navItems = listOf(
        "General", "Appearance", "Services & Presets",
        "Plugins", "Keyboard & Hotkeys", "Translation", "Window & Layout"
    )
    private val tree: JTree

    // Content area
    private val contentArea  = JPanel(BorderLayout())
    private val panelTitle   = JLabel("General").apply {
        font   = font.deriveFont(Font.BOLD, font.size + 2f)
        border = BorderFactory.createEmptyBorder(0, 0, 0, 12)
    }
    private val dirtyDot = JLabel("● Unsaved changes").apply {
        foreground = UIManager.getColor("Actions.Yellow") ?: Color(0xE65100)
        font       = font.deriveFont(font.size - 1f)
        isVisible  = false
    }

    // Panels — created lazily on first navigation
    private val panelCache = mutableMapOf<String, JPanel>()
    private var currentPanelName: String? = null

    // Buttons
    private lateinit var applyButton: JButton
    private lateinit var okButton:    JButton

    init {
        layout = BorderLayout()

        // ---- Tree navigation ----
        val root = DefaultMutableTreeNode("root")
        navItems.forEach { root.add(DefaultMutableTreeNode(it)) }

        tree = JTree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            showsRootHandles = false
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            putClientProperty("FlatLaf.style",
                "rowHeight: 34; selectionArc: 8; selectionInsets: 2,2,2,2")

            cellRenderer = object : DefaultTreeCellRenderer() {
                init { leafIcon = null; closedIcon = null; openIcon = null }
                override fun getTreeCellRendererComponent(
                    tree: JTree, value: Any, sel: Boolean,
                    expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
                ): Component {
                    super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                    border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
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
            minimumSize   = Dimension(190, 0)
            preferredSize = Dimension(200, 0)
            border = BorderFactory.createMatteBorder(
                0, 0, 0, 1, UIManager.getColor("Component.borderColor") ?: Color.GRAY
            )
        }

        // ---- Panel header strip ----
        val headerStrip = JPanel(FlowLayout(FlowLayout.LEFT, 12, 10)).apply {
            border = BorderFactory.createMatteBorder(
                0, 0, 1, 0, UIManager.getColor("Component.borderColor") ?: Color.GRAY
            )
            add(panelTitle)
            add(dirtyDot)
        }

        contentArea.add(headerStrip, BorderLayout.NORTH)

        // ---- Split pane ----
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, contentArea).apply {
            dividerSize     = 1
            resizeWeight    = 0.0
            dividerLocation = 200
            border          = null
        }

        add(split,              BorderLayout.CENTER)
        add(buildButtonBar(),   BorderLayout.SOUTH)

        // ESC → cancel
        rootPane.registerKeyboardAction(
            { settingsStore.dispatch(SettingsIntent.CancelChanges); dispose() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        // Window close → cancel
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                settingsStore.dispatch(SettingsIntent.CancelChanges)
            }
        })

        observeState()

        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize   = Dimension(860, 580)
        preferredSize = Dimension(1000, 680)
        pack()
        setLocationRelativeTo(owner)

        // Select first item
        tree.setSelectionRow(0)
    }

    // -------------------------------------------------------------------------
    // Panel management
    // -------------------------------------------------------------------------

    private fun showPanel(name: String) {
        currentPanelName = name
        panelTitle.text  = name

        val panel = panelCache.getOrPut(name) { createPanel(name) }

        // Remove previous scroll wrapper
        contentArea.components
            .filterIsInstance<JScrollPane>()
            .forEach { contentArea.remove(it) }

        val scroll = JScrollPane(panel).apply {
            border = null
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        contentArea.add(scroll, BorderLayout.CENTER)
        contentArea.revalidate()
        contentArea.repaint()

        // Render with current state
        if (panel is Renderable<*>) {
            @Suppress("UNCHECKED_CAST")
            (panel as Renderable<SettingsState>).render(settingsStore.state.value)
        }
    }

    private fun createPanel(name: String): JPanel = when (name) {
        "General"            -> GeneralPanel(settingsStore)
        "Appearance"         -> AppearancePanel(settingsStore, themeManager)
        "Services & Presets" -> ServicesPanel(settingsStore, pluginManager, scope)
        "Plugins"            -> PluginsPanel(iconManager, pluginManager, scope)
        "Keyboard & Hotkeys" -> KeyboardPanel(settingsStore)
        "Translation"        -> TranslationPanel(settingsStore)
        "Window & Layout"    -> WindowPanel(settingsStore)
        else                 -> JPanel()
    }

    // -------------------------------------------------------------------------
    // Button bar
    // -------------------------------------------------------------------------

    private fun buildButtonBar(): JPanel {
        okButton = JButton("OK").apply {
            mnemonic = KeyEvent.VK_O
            addActionListener { onOk() }
        }
        val cancelButton = JButton("Cancel").apply {
            mnemonic = KeyEvent.VK_C
            addActionListener {
                settingsStore.dispatch(SettingsIntent.CancelChanges)
                dispose()
            }
        }
        applyButton = JButton("Apply").apply {
            mnemonic  = KeyEvent.VK_A
            isEnabled = false
            addActionListener { settingsStore.dispatch(SettingsIntent.SaveChanges) }
        }
        val resetButton = JButton("Reset to Defaults").apply {
            mnemonic = KeyEvent.VK_R
            addActionListener { onReset() }
        }

        return JPanel(GridBagLayout()).apply {
            border = BorderFactory.createMatteBorder(
                1, 0, 0, 0, UIManager.getColor("Component.borderColor") ?: Color.GRAY
            )
            val gbc = GridBagConstraints().apply { gridy = 0; insets = Insets(8, 8, 8, 4) }

            gbc.gridx = 0; gbc.weightx = 1.0; gbc.anchor = GridBagConstraints.WEST
            add(resetButton, gbc)

            gbc.gridx = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.EAST
            add(okButton,     gbc)
            gbc.gridx = 2; add(cancelButton, gbc)
            gbc.gridx = 3; gbc.insets = Insets(8, 4, 8, 8); add(applyButton, gbc)
        }
    }

    // -------------------------------------------------------------------------
    // State observation
    // -------------------------------------------------------------------------

    private fun observeState() {
        scope.launch {
            settingsStore.state.collect { state ->
                withContext(Dispatchers.Swing) {
                    // Update dirty indicator and button states
                    dirtyDot.isVisible    = state.isDirty
                    applyButton.isEnabled = state.isDirty

                    // Re-render the currently visible panel
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

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private fun onOk() {
        okButton.isEnabled = false
        okButton.text      = "Saving…"

        settingsStore.dispatch(SettingsIntent.SaveChanges)

        // Wait for the save to complete (success or failure) then close
        scope.launch {
            settingsStore.events.collect { event ->
                withContext(Dispatchers.Swing) {
                    when (event) {
                        is SettingsEvent.ShowMessage -> {
                            if (event.type != NotificationType.ERROR) {
                                dispose()
                            } else {
                                okButton.isEnabled = true
                                okButton.text      = "OK"
                                JOptionPane.showMessageDialog(
                                    this@SettingsDialog,
                                    event.message,
                                    "Save Failed",
                                    JOptionPane.ERROR_MESSAGE
                                )
                            }
                        }
                        // CloseSettingsDialog is also a signal to close
                        SettingsEvent.CloseSettingsDialog -> dispose()
                    }
                }
                return@collect
            }
        }
    }

    private fun onReset() {
        val result = JOptionPane.showConfirmDialog(
            this,
            "Reset all settings to their defaults?\nThis cannot be undone.",
            "Reset to Defaults",
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