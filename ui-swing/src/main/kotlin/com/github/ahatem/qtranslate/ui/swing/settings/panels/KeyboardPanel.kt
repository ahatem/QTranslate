package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyPresetKind
import com.github.ahatem.qtranslate.core.settings.data.HotkeyPresets
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

class KeyboardPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager,
    /** Called just before the hotkey recorder dialog opens to prevent accidental triggers. */
    private val pauseGlobalHotkeys:  (() -> Unit)? = null,
    /** Called after the recorder dialog closes to restore the previous enabled state. */
    private val resumeGlobalHotkeys: (() -> Unit)? = null,
) : SettingsPanel() {

    private lateinit var enableCheck: JCheckBox
    private lateinit var showShortcut: JPanel
    private lateinit var showEditButton: JButton
    private lateinit var showClearButton: JButton
    private lateinit var doubleCtrlCheck: JCheckBox
    private lateinit var table:       JTable
    private lateinit var editButton:  JButton
    private lateinit var clearButton: JButton
    private lateinit var resetButton: JButton
    private lateinit var presetCombo: JComboBox<HotkeyPresetKind>

    private val actionOrder = listOf(
        HotkeyAction.SHOW_QUICK_TRANSLATE,
        HotkeyAction.LISTEN_TO_TEXT,
        HotkeyAction.OPEN_OCR,
        HotkeyAction.REPLACE_WITH_TRANSLATION,
        HotkeyAction.CYCLE_TARGET_LANGUAGE,
        HotkeyAction.SHOW_DICTIONARY,
        HotkeyAction.SHOW_IMAGES,
        HotkeyAction.TRANSLATE,
        HotkeyAction.FOCUS_INPUT,
        HotkeyAction.FOCUS_OUTPUT,
        HotkeyAction.FOCUS_EXTRA_OUTPUT
    )

    private val nonEditableActions    = emptySet<HotkeyAction>()

    /**
     * Table metrics, authored for a 100% display and scaled where they are used: a table row and
     * its columns are plain pixel counts to Swing, and left raw they stay the same size while the
     * text and key chips inside them grow with the display.
     */
    private companion object {
        const val ROW_HEIGHT = 34
        const val ACTION_MIN_WIDTH = 170
        const val ACTION_PREFERRED_WIDTH = 310
        const val HOTKEY_MIN_WIDTH = 125
        const val HOTKEY_PREFERRED_WIDTH = 190
        const val HOTKEY_MAX_WIDTH = 320
        const val SCOPE_MIN_WIDTH = 64
        const val SCOPE_PREFERRED_WIDTH = 80
        const val SCOPE_MAX_WIDTH = 120
        const val CHIP_ARC = 8
        const val CHIP_PADDING_Y = 4
        const val CHIP_PADDING_X = 9
        const val CHIP_GAP = 4
        const val TABLE_PREFERRED_WIDTH = 580
    }

    private val COL_ACTION = 0
    private val COL_HOTKEY = 1
    private val COL_SCOPE  = 2

    init { buildUI() }

    private fun buildUI() {
        addSeparator(localizationManager.getString("settings_hotkeys.global_group"))

        enableCheck = addCheckbox(
            text     = localizationManager.getString("settings_hotkeys.enable_global"),
            selected = false,
            onChange = { enabled ->
                applyDraft(store) { it.copy(isGlobalHotkeysEnabled = enabled) }
            }
        )

        addSeparator(localizationManager.getString("settings_hotkeys.show_main_group"))

        showShortcut = JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(4), 0)).apply { isOpaque = false }
        showEditButton = JButton(localizationManager.getString("settings_hotkeys.change_button"))
        showClearButton = JButton(localizationManager.getString("settings_hotkeys.clear_button"))
        showEditButton.addActionListener { onEditShowMain() }
        showClearButton.addActionListener { onClearShowMain() }
        val showShortcutGroup = JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(6), 0)).apply {
            isOpaque = false
            add(showShortcut)
            add(showEditButton)
            add(showClearButton)
        }
        addRow(localizationManager.getString("settings_hotkeys.shortcut_label"), showShortcutGroup)
        addRow(
            localizationManager.getString("settings_hotkeys.column_scope"),
            JLabel(localizationManager.getString("settings_hotkeys.scope_global"))
        )

        doubleCtrlCheck = addCheckbox(
            text = localizationManager.getString("settings_hotkeys.double_ctrl_label"),
            selected = true,
            onChange = { enabled -> onToggleDoubleCtrl(enabled) }
        )
        addHint(localizationManager.getString("settings_hotkeys.double_ctrl_description"))

        addSeparator(localizationManager.getString("settings_hotkeys.assignments_group"))
        presetCombo = JComboBox(HotkeyPresetKind.values()).apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean
                ): Component = super.getListCellRendererComponent(
                    list, presetLabel(value as? HotkeyPresetKind ?: HotkeyPresetKind.CUSTOM), index, selected, focused
                )
            }
            addActionListener {
                if (!isUpdatingFromState) {
                    val preset = selectedItem as? HotkeyPresetKind ?: return@addActionListener
                    applyDraft(store) { HotkeyDraftOperations.replacePreset(it, preset) }
                }
            }
        }
        addRow(localizationManager.getString("settings_hotkeys.preset_label"), presetCombo)
        addHint(localizationManager.getString("settings_hotkeys.edit_hint"))

        val model = object : DefaultTableModel(
            arrayOf(
                localizationManager.getString("settings_hotkeys.column_action"),
                localizationManager.getString("settings_hotkeys.column_hotkey"),
                localizationManager.getString("settings_hotkeys.column_scope")
            ), 0
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
            override fun getColumnClass(col: Int) = String::class.java
        }

        actionOrder.forEach { action ->
            model.addRow(arrayOf<Any>(actionDisplayName(action), "", scopeLabel(HotkeyScope.GLOBAL)))
        }

        table = JTable(model).apply {
            fillsViewportHeight = true
            rowHeight           = UIScale.scale(ROW_HEIGHT)
            autoResizeMode      = JTable.AUTO_RESIZE_ALL_COLUMNS
            setShowGrid(false)
            intercellSpacing    = Dimension(0, 0)
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            putClientProperty("FlatLaf.style", "showCellFocusIndicator: false")

            // Hotkey and Scope are capped so that extra width goes to the Action names, which are
            // the text people read, rather than stretching a column that holds a few key chips.
            columnModel.getColumn(COL_ACTION).apply {
                preferredWidth = UIScale.scale(ACTION_PREFERRED_WIDTH)
                minWidth       = UIScale.scale(ACTION_MIN_WIDTH)
            }
            columnModel.getColumn(COL_HOTKEY).apply {
                preferredWidth = UIScale.scale(HOTKEY_PREFERRED_WIDTH)
                minWidth       = UIScale.scale(HOTKEY_MIN_WIDTH)
                maxWidth       = UIScale.scale(HOTKEY_MAX_WIDTH)
                cellRenderer   = HotkeyColumnRenderer()
            }
            columnModel.getColumn(COL_SCOPE).apply {
                preferredWidth = UIScale.scale(SCOPE_PREFERRED_WIDTH)
                minWidth       = UIScale.scale(SCOPE_MIN_WIDTH)
                maxWidth       = UIScale.scale(SCOPE_MAX_WIDTH)
                cellRenderer   = ScopeColumnRenderer()
            }

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (row < 0) return
                    when {
                        // Double-click hotkey column — open recorder
                        col == COL_HOTKEY && e.clickCount == 2 -> {
                            setRowSelectionInterval(row, row)
                            onEditSelected()
                        }
                        // Single click scope column — toggle global/local
                        col == COL_SCOPE -> {
                            setRowSelectionInterval(row, row)
                            onToggleScope(row)
                        }
                    }
                }
            })

            selectionModel.addListSelectionListener { updateButtonStates() }
        }

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(4), 0, 0, 0)
            .add(JScrollPane(table).apply {
                border = themeAwareBorder()
                // Every row and the header, so nothing is hidden behind a scrollbar by default.
                preferredSize = Dimension(
                    UIScale.scale(TABLE_PREFERRED_WIDTH),
                    table.rowHeight * actionOrder.size + table.tableHeader.preferredSize.height + UIScale.scale(4)
                )
            })

        editButton  = JButton(localizationManager.getString("settings_hotkeys.edit_button"))
        clearButton = JButton(localizationManager.getString("settings_hotkeys.clear_button"))
        resetButton = JButton(localizationManager.getString("settings_hotkeys.reset_button"))

        editButton.isEnabled  = false
        clearButton.isEnabled = false

        editButton.addActionListener  { onEditSelected() }
        clearButton.addActionListener { onClearSelected() }
        resetButton.addActionListener { onResetAll() }

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(6), 0, 0, 0)
            .add(JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(4), 0)).apply {
                isOpaque = false
                add(editButton)
                add(clearButton)
                add(resetButton)
            })

        finishLayout()
    }


    private fun onEditSelected() {
        val row    = table.selectedRow.takeIf { it >= 0 } ?: return
        val action = actionOrder[row]
        if (action in nonEditableActions) return

        val current = store.state.value.workingConfiguration
            .hotkeys.find { it.action == action }

        val result = HotkeyRecorderDialog.show(
            owner               = SwingUtilities.getWindowAncestor(this),
            action              = action,
            current             = current,
            localizer           = localizationManager,
            pauseGlobalHotkeys  = pauseGlobalHotkeys,
            resumeGlobalHotkeys = resumeGlobalHotkeys,
        ) ?: return

        saveBinding(result)
    }

    private fun onEditShowMain() {
        val current = bindingFor(HotkeyAction.SHOW_MAIN_WINDOW) ?: return
        val result = HotkeyRecorderDialog.show(
            owner = SwingUtilities.getWindowAncestor(this),
            action = HotkeyAction.SHOW_MAIN_WINDOW,
            current = current,
            localizer = localizationManager,
            pauseGlobalHotkeys = pauseGlobalHotkeys,
            resumeGlobalHotkeys = resumeGlobalHotkeys,
        ) ?: return
        saveBinding(result.copy(scope = HotkeyScope.GLOBAL, isDoubleCtrlEnabled = current.isDoubleCtrlEnabled))
    }

    private fun onClearShowMain() {
        applyDraft(store) { HotkeyDraftOperations.clearShowMainWindow(it) }
    }

    private fun onToggleDoubleCtrl(enabled: Boolean) {
        applyDraft(store) { HotkeyDraftOperations.setDoubleCtrl(it, enabled) }
    }

    private fun onClearSelected() {
        val row    = table.selectedRow.takeIf { it >= 0 } ?: return
        val action = actionOrder[row]
        if (action in nonEditableActions) return
        // Keep existing scope when clearing
        val existing = store.state.value.workingConfiguration.hotkeys.find { it.action == action }
        saveBinding(HotkeyBinding(action = action, keyCode = 0, modifiers = 0, scope = existing?.scope ?: HotkeyScope.GLOBAL))
    }

    private fun onToggleScope(row: Int) {
        val action = actionOrder[row]
        if (bindingFor(action) == null) return
        applyDraft(store) { HotkeyDraftOperations.toggleScope(it, action) }
    }

    private fun onResetAll() {
        val confirmed = JOptionPane.showConfirmDialog(
            this,
            localizationManager.getString("settings_hotkeys.reset_confirmation"),
            localizationManager.getString("settings_hotkeys.reset_title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION
        if (!confirmed) return
        applyDraft(store) { it.copy(hotkeys = HotkeyPresets.LEGACY.map { binding -> binding.copy() }) }
    }

    private fun saveBinding(binding: HotkeyBinding) {
        applyDraft(store) { HotkeyDraftOperations.replaceBinding(it, binding) }
    }

    /** The bindings table, for tests that check how it is sized. */
    internal fun tableForTest(): JTable = table

    private fun bindingFor(action: HotkeyAction): HotkeyBinding? =
        store.state.value.workingConfiguration.hotkeys.find { it.action == action }

    private fun tokenizeBinding(binding: HotkeyBinding): List<String> = buildList {
        if (binding.modifiers and InputEvent.CTRL_DOWN_MASK != 0) add("Ctrl")
        if (binding.modifiers and InputEvent.ALT_DOWN_MASK != 0) add("Alt")
        if (binding.modifiers and InputEvent.SHIFT_DOWN_MASK != 0) add("Shift")
        if (binding.modifiers and InputEvent.META_DOWN_MASK != 0) add("⌘")
        add(KeyEvent.getKeyText(binding.keyCode))
    }

    private fun refreshShowShortcut(binding: HotkeyBinding?) {
        showShortcut.removeAll()
        if (binding == null || !binding.hasBinding) {
            showShortcut.add(JLabel(localizationManager.getString("settings_hotkeys.no_binding")).apply {
                foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            })
        } else {
            tokenizeBinding(binding).forEach { showShortcut.add(KeyChip(it, false)) }
        }
        showShortcut.revalidate()
        showShortcut.repaint()
    }

    private fun updateButtonStates() {
        val row        = table.selectedRow
        val isEditable = row >= 0 && actionOrder.getOrNull(row) !in nonEditableActions
        val enabled    = enableCheck.isSelected
        editButton.isEnabled  = isEditable && enabled
        clearButton.isEnabled = isEditable && enabled
    }


    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            enableCheck.isSelected = c.isGlobalHotkeysEnabled
            presetCombo.selectedItem = HotkeyPresets.identify(c.hotkeys)

            val showBinding = c.hotkeys.find { it.action == HotkeyAction.SHOW_MAIN_WINDOW }
            refreshShowShortcut(showBinding)
            doubleCtrlCheck.isSelected = showBinding?.isDoubleCtrlEnabled ?: true
            showEditButton.isEnabled = c.isGlobalHotkeysEnabled
            showClearButton.isEnabled = c.isGlobalHotkeysEnabled && showBinding?.hasBinding == true

            val model = table.model as DefaultTableModel
            actionOrder.forEachIndexed { row, action ->
                val binding = c.hotkeys.find { it.action == action }
                model.setValueAt(binding, row, COL_HOTKEY)
                model.setValueAt(scopeLabel(binding?.scope ?: HotkeyScope.GLOBAL), row, COL_SCOPE)
            }

            table.isEnabled = c.isGlobalHotkeysEnabled
            doubleCtrlCheck.isEnabled = c.isGlobalHotkeysEnabled
            updateButtonStates()
        }
    }


    private fun actionDisplayName(action: HotkeyAction): String = when (action) {
        HotkeyAction.SHOW_MAIN_WINDOW         -> localizationManager.getString("settings_hotkeys.action_show_main")
        HotkeyAction.SHOW_QUICK_TRANSLATE     -> localizationManager.getString("settings_hotkeys.action_quick_translate")
        HotkeyAction.LISTEN_TO_TEXT           -> localizationManager.getString("settings_hotkeys.action_listen")
        HotkeyAction.OPEN_OCR                 -> localizationManager.getString("settings_hotkeys.action_ocr")
        HotkeyAction.REPLACE_WITH_TRANSLATION -> localizationManager.getString("settings_hotkeys.action_replace")
        HotkeyAction.CYCLE_TARGET_LANGUAGE    -> localizationManager.getString("settings_hotkeys.action_cycle_language")
        HotkeyAction.SHOW_DICTIONARY          -> localizationManager.getString("settings_hotkeys.action_show_dictionary")
        HotkeyAction.SHOW_IMAGES              -> localizationManager.getString("settings_hotkeys.action_show_images")
        HotkeyAction.TRANSLATE                -> localizationManager.getString("settings_hotkeys.action_translate")
        HotkeyAction.FOCUS_INPUT              -> localizationManager.getString("settings_hotkeys.action_focus_input")
        HotkeyAction.FOCUS_OUTPUT             -> localizationManager.getString("settings_hotkeys.action_focus_output")
        HotkeyAction.FOCUS_EXTRA_OUTPUT       -> localizationManager.getString("settings_hotkeys.action_focus_extra_output")
        HotkeyAction.COPY_TRANSLATION         -> localizationManager.getString("settings_hotkeys.action_copy_translation")
        HotkeyAction.CLEAR_INPUT              -> localizationManager.getString("settings_hotkeys.action_clear_input")
        HotkeyAction.SWAP_LANGUAGES           -> localizationManager.getString("settings_hotkeys.action_swap_languages")
        HotkeyAction.OPEN_SETTINGS            -> localizationManager.getString("settings_hotkeys.action_open_settings")
        HotkeyAction.SHOW_HISTORY             -> localizationManager.getString("settings_hotkeys.action_show_history")
        HotkeyAction.TRANSLATE_DOCUMENT       -> localizationManager.getString("settings_hotkeys.action_translate_document")
    }

    private fun scopeLabel(scope: HotkeyScope): String = when (scope) {
        HotkeyScope.GLOBAL -> localizationManager.getString("settings_hotkeys.scope_global")
        HotkeyScope.LOCAL  -> localizationManager.getString("settings_hotkeys.scope_local")
    }

    private fun presetLabel(preset: HotkeyPresetKind): String = when (preset) {
        HotkeyPresetKind.LEGACY -> localizationManager.getString("settings_hotkeys.preset_legacy")
        HotkeyPresetKind.MODERN -> localizationManager.getString("settings_hotkeys.preset_modern")
        HotkeyPresetKind.CUSTOM -> localizationManager.getString("settings_hotkeys.preset_custom")
    }

    /**
     * Renders keyboard bindings as pill-shaped key chip badges — e.g. [Ctrl] [Alt] [T].
     * Each token is drawn as a custom component with a rounded border, matching modern
     * OS-style hotkey displays (VS Code, macOS System Settings, JetBrains IDEs).
     */
    private inner class HotkeyColumnRenderer : TableCellRenderer {

        override fun getTableCellRendererComponent(
            t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
        ): Component {
            val binding = value as? HotkeyBinding
            val bg      = if (sel) t.selectionBackground else t.background

            return when {
                binding == null || !binding.hasBinding ->
                    chipRow(
                        listOf(localizationManager.getString("settings_hotkeys.no_binding")),
                        bg, muted = true
                    )
                else ->
                    chipRow(
                        tokenizeBinding(binding), bg, muted = false,
                        tooltip = null
                    )
            }
        }

        /**
         * Builds a row of key chips, vertically and horizontally centered in the table cell.
         * Uses a GridBagLayout outer panel so the inner FlowLayout strip sits in the middle
         * of the fixed-height row rather than being pinned to the top.
         */
        private fun chipRow(
            tokens: List<String>,
            bg: Color,
            muted: Boolean,
            tooltip: String? = null
        ): JPanel = JPanel(GridBagLayout()).apply {
            background  = bg
            toolTipText = tooltip
            val inner = JPanel(FlowLayout(FlowLayout.CENTER, UIScale.scale(CHIP_GAP), 0)).apply {
                isOpaque = false
                tokens.forEach { add(KeyChip(it, muted)) }
            }
            add(inner)   // default GridBagConstraints: anchor = CENTER, no fill
        }

        /** Splits a binding into individual displayable key tokens. */
        private fun tokenizeBinding(binding: HotkeyBinding): List<String> = buildList {
            if (binding.modifiers and InputEvent.CTRL_DOWN_MASK  != 0) add("Ctrl")
            if (binding.modifiers and InputEvent.ALT_DOWN_MASK   != 0) add("Alt")
            if (binding.modifiers and InputEvent.SHIFT_DOWN_MASK != 0) add("Shift")
            if (binding.modifiers and InputEvent.META_DOWN_MASK  != 0) add("⌘")
            add(KeyEvent.getKeyText(binding.keyCode))
        }
    }

    /**
     * A single keyboard key rendered as a rounded-rectangle badge.
     * Multi-platform friendly + FlatLaf compatible.
     * Clean single-border style (no nested box effect).
     */
    private inner class KeyChip(label: String, private val muted: Boolean) : JLabel(label) {

        private val arc = UIScale.scale(CHIP_ARC)
        private val borderAlpha = if (muted) 90 else 150

        init {
            isOpaque = false
            horizontalAlignment = CENTER
            verticalAlignment = CENTER

            // Multi-platform mono font selection
            font = createMonoFont(
                size = this@KeyboardPanel.font.size - 1,
                style = if (muted) Font.ITALIC else Font.PLAIN
            )

            foreground = if (muted) {
                UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            } else {
                UIManager.getColor("Label.foreground") ?: Color.BLACK
            }

            // Balanced padding
            border = BorderFactory.createEmptyBorder(
                UIScale.scale(CHIP_PADDING_Y), UIScale.scale(CHIP_PADDING_X),
                UIScale.scale(CHIP_PADDING_Y), UIScale.scale(CHIP_PADDING_X)
            )
        }

        private fun createMonoFont(size: Int, style: Int): Font {
            val candidates = arrayOf(
                "JetBrains Mono",
                "Cascadia Mono",
                "Cascadia Code",
                "Menlo",
                "Monaco",
                "Consolas",
                "DejaVu Sans Mono",
                "Liberation Mono",
                "Courier New",
                "Courier",
                Font.MONOSPACED
            )

            for (name in candidates) {
                val f = Font(name, style, size)
                if (f.family != "Dialog") {
                    return f
                }
            }
            return Font(Font.MONOSPACED, style, size)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val bg = if (muted) {
                    UIManager.getColor("Panel.background")?.let {
                        Color(it.red, it.green, it.blue, 35)
                    } ?: Color(0, 0, 0, 20)
                } else {
                    UIManager.getColor("Button.background") ?: Color(235, 235, 235)
                }

                val borderColor = (UIManager.getColor("Component.borderColor") ?: Color(160, 160, 160))
                    .let { Color(it.red, it.green, it.blue, borderAlpha) }

                // Background
                g2.color = bg
                g2.fillRoundRect(0, 0, width, height, arc, arc)

                // Single clean border
                g2.color = borderColor
                g2.stroke = BasicStroke(1f)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

            } finally {
                g2.dispose()
            }

            // Let Swing + FlatLaf handle the text rendering
            super.paintComponent(g)
        }
    }

    private inner class ScopeColumnRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.CENTER }

        override fun getTableCellRendererComponent(
            t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
        ): Component {
            super.getTableCellRendererComponent(t, value, sel, focus, row, col)
            val action = actionOrder.getOrNull(row)

            val label = value as? String ?: ""
            text       = label
            foreground = if (sel) UIManager.getColor("Table.selectionForeground")
            else     UIManager.getColor("Component.accentColor")
                ?: UIManager.getColor("Table.foreground")
            font        = font.deriveFont(Font.PLAIN)
            toolTipText = localizationManager.getString("settings_hotkeys.scope_toggle_hint")
            return this
        }
    }

}
