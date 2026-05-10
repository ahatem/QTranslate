package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
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
    private val localizationManager: LocalizationManager
) : SettingsPanel() {

    private lateinit var enableCheck: JCheckBox
    private lateinit var table:       JTable
    private lateinit var editButton:  JButton
    private lateinit var clearButton: JButton
    private lateinit var resetButton: JButton

    private val actionOrder = listOf(
        HotkeyAction.SHOW_MAIN_WINDOW,
        HotkeyAction.SHOW_QUICK_TRANSLATE,
        HotkeyAction.LISTEN_TO_TEXT,
        HotkeyAction.OPEN_OCR,
        HotkeyAction.REPLACE_WITH_TRANSLATION,
        HotkeyAction.CYCLE_TARGET_LANGUAGE,
        HotkeyAction.SHOW_DICTIONARY,
        HotkeyAction.TRANSLATE
    )

    // SHOW_MAIN_WINDOW can now have a custom keystroke — only its scope is locked to GLOBAL.
    private val nonEditableActions    = emptySet<HotkeyAction>()
    private val nonScopeToggleActions = setOf(HotkeyAction.SHOW_MAIN_WINDOW)

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

        addSeparator(localizationManager.getString("settings_hotkeys.assignments_group"))
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
            rowHeight           = 34
            setShowGrid(false)
            intercellSpacing    = Dimension(0, 0)
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            putClientProperty("FlatLaf.style", "showCellFocusIndicator: false")

            columnModel.getColumn(COL_ACTION).apply { preferredWidth = 200; minWidth = 150 }
            columnModel.getColumn(COL_HOTKEY).apply {
                preferredWidth = 150
                minWidth       = 110
                cellRenderer   = HotkeyColumnRenderer()
            }
            columnModel.getColumn(COL_SCOPE).apply {
                preferredWidth = 130
                minWidth       = 80
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
            .insets(4, 0, 0, 0)
            .add(JScrollPane(table).apply {
                preferredSize = Dimension(580, actionOrder.size * 34 + 4)
                border = themeAwareBorder()
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
            .insets(6, 0, 0, 0)
            .add(JPanel(FlowLayout(FlowLayout.LEADING, 4, 0)).apply {
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
            owner      = SwingUtilities.getWindowAncestor(this),
            action     = action,
            current    = current,
            localizer  = localizationManager
        ) ?: return

        saveBinding(result)
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
        if (action == HotkeyAction.SHOW_MAIN_WINDOW) {
            // For SHOW_MAIN_WINDOW the scope is locked to GLOBAL, but the cell
            // acts as a "Double Ctrl" on/off toggle instead.
            val current = store.state.value.workingConfiguration.hotkeys
                .find { it.action == action } ?: return
            saveBinding(current.copy(isDoubleCtrlEnabled = !current.isDoubleCtrlEnabled))
            return
        }
        if (action in nonScopeToggleActions) return
        val current = store.state.value.workingConfiguration.hotkeys.find { it.action == action }
            ?: return
        val newScope = if (current.scope == HotkeyScope.GLOBAL) HotkeyScope.LOCAL else HotkeyScope.GLOBAL
        saveBinding(current.copy(scope = newScope))
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
        store.dispatch(SettingsIntent.ToggleSetting { it.copy(hotkeys = HotkeyBinding.DEFAULTS) })
    }

    private fun saveBinding(binding: HotkeyBinding) {
        store.dispatch(SettingsIntent.ToggleSetting { config ->
            val updated = config.hotkeys.map { b ->
                if (b.action == binding.action) binding else b
            }
            val final = if (updated.any { it.action == binding.action }) updated
            else updated + binding
            config.copy(hotkeys = final)
        })
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

            val model = table.model as DefaultTableModel
            actionOrder.forEachIndexed { row, action ->
                val binding = c.hotkeys.find { it.action == action }
                model.setValueAt(binding, row, COL_HOTKEY)
                model.setValueAt(scopeLabel(binding?.scope ?: HotkeyScope.GLOBAL), row, COL_SCOPE)
            }

            table.isEnabled = c.isGlobalHotkeysEnabled
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
        HotkeyAction.TRANSLATE                -> localizationManager.getString("settings_hotkeys.action_translate")
    }

    private fun scopeLabel(scope: HotkeyScope): String = when (scope) {
        HotkeyScope.GLOBAL -> localizationManager.getString("settings_hotkeys.scope_global")
        HotkeyScope.LOCAL  -> localizationManager.getString("settings_hotkeys.scope_local")
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
            val action  = actionOrder.getOrNull(row)
            val binding = value as? HotkeyBinding
            val bg      = if (sel) t.selectionBackground else t.background

            return when {
                action == HotkeyAction.SHOW_MAIN_WINDOW && (binding == null || !binding.hasBinding) ->
                    chipRow(
                        listOf(localizationManager.getString("settings_hotkeys.double_ctrl")),
                        bg, muted = true,
                        tooltip = localizationManager.getString("settings_hotkeys.show_main_tooltip")
                    )
                binding == null || !binding.hasBinding ->
                    chipRow(
                        listOf(localizationManager.getString("settings_hotkeys.no_binding")),
                        bg, muted = true
                    )
                else ->
                    chipRow(
                        tokenizeBinding(binding), bg, muted = false,
                        tooltip = if (action == HotkeyAction.SHOW_MAIN_WINDOW)
                            localizationManager.getString("settings_hotkeys.show_main_tooltip") else null
                    )
            }
        }

        /** Builds a centered row of key chips. */
        private fun chipRow(
            tokens: List<String>,
            bg: Color,
            muted: Boolean,
            tooltip: String? = null
        ): JPanel = JPanel(FlowLayout(FlowLayout.CENTER, 4, 0)).apply {
            background  = bg
            toolTipText = tooltip
            tokens.forEach { add(KeyChip(it, muted)) }
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
     * Matches the visual style of key chips in modern IDEs and OS preference panes.
     */
    private inner class KeyChip(private val label: String, private val muted: Boolean) : JComponent() {
        private val hPad = 8
        private val vPad = 3
        private val arc  = 6

        init { isOpaque = false }

        override fun getPreferredSize(): Dimension {
            val fm = getFontMetrics(chipFont())
            return Dimension(fm.stringWidth(label) + hPad * 2, fm.height + vPad * 2)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            if (!muted) {
                val chipBg     = UIManager.getColor("Button.background")     ?: Color(235, 235, 235)
                val chipBorder = UIManager.getColor("Component.borderColor") ?: Color(180, 180, 180)
                g2.color = chipBg
                g2.fillRoundRect(0, 0, width, height, arc, arc)
                g2.color = Color(chipBorder.red, chipBorder.green, chipBorder.blue, 180)
                g2.stroke = BasicStroke(1f)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }

            val fg = if (muted) UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                     else       UIManager.getColor("Label.foreground")          ?: Color.BLACK
            g2.color = fg
            g2.font  = chipFont()
            val fm   = g2.fontMetrics
            g2.drawString(label,
                (width - fm.stringWidth(label)) / 2,
                (height + fm.ascent - fm.descent) / 2)
        }

        private fun chipFont() = Font(Font.MONOSPACED, if (muted) Font.ITALIC else Font.PLAIN,
            this@KeyboardPanel.font.size - 1)
    }

    private inner class ScopeColumnRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.CENTER }

        override fun getTableCellRendererComponent(
            t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
        ): Component {
            super.getTableCellRendererComponent(t, value, sel, focus, row, col)
            val action = actionOrder.getOrNull(row)

            if (action == HotkeyAction.SHOW_MAIN_WINDOW) {
                // Repurpose scope cell as a "Double Ctrl" on/off toggle.
                val binding = store.state.value.workingConfiguration.hotkeys
                    .find { it.action == action }
                val enabled = binding?.isDoubleCtrlEnabled ?: true
                text        = if (enabled)
                    localizationManager.getString("settings_hotkeys.double_ctrl_on")
                else
                    localizationManager.getString("settings_hotkeys.double_ctrl_off")
                foreground  = if (enabled)
                    (UIManager.getColor("Component.accentColor") ?: UIManager.getColor("Table.foreground"))
                else
                    UIManager.getColor("Label.disabledForeground")
                font        = font.deriveFont(Font.PLAIN)
                toolTipText = localizationManager.getString("settings_hotkeys.double_ctrl_toggle_hint")
                return this
            }

            val label = value as? String ?: ""
            if (action in nonScopeToggleActions) {
                text        = label
                foreground  = UIManager.getColor("Label.disabledForeground")
                font        = font.deriveFont(Font.ITALIC)
                toolTipText = null
            } else {
                text       = label
                foreground = if (sel) UIManager.getColor("Table.selectionForeground")
                else     UIManager.getColor("Component.accentColor")
                    ?: UIManager.getColor("Table.foreground")
                font       = font.deriveFont(Font.PLAIN)
                toolTipText = localizationManager.getString("settings_hotkeys.scope_toggle_hint")
            }
            return this
        }
    }

    companion object {
        fun formatBinding(binding: HotkeyBinding): String {
            if (!binding.hasBinding) return ""
            val mods = buildString {
                if (binding.modifiers and InputEvent.CTRL_DOWN_MASK  != 0) append("Ctrl + ")
                if (binding.modifiers and InputEvent.ALT_DOWN_MASK   != 0) append("Alt + ")
                if (binding.modifiers and InputEvent.SHIFT_DOWN_MASK != 0) append("Shift + ")
                if (binding.modifiers and InputEvent.META_DOWN_MASK  != 0) append("⌘ + ")
            }
            return mods + KeyEvent.getKeyText(binding.keyCode)
        }
    }
}

object HotkeyRecorderDialog {

    fun show(
        owner: Window?,
        action: HotkeyAction,
        current: HotkeyBinding?,
        localizer: LocalizationManager
    ): HotkeyBinding? {

        var capturedKeyCode   = current?.keyCode   ?: 0
        var capturedModifiers = current?.modifiers  ?: 0

        val dialog = JDialog(
            owner,
            localizer.getString("settings_hotkeys.recorder_title"),
            Dialog.ModalityType.APPLICATION_MODAL
        )
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

        val actionName = when (action) {
            HotkeyAction.SHOW_MAIN_WINDOW         -> localizer.getString("settings_hotkeys.action_show_main")
            HotkeyAction.SHOW_QUICK_TRANSLATE     -> localizer.getString("settings_hotkeys.action_quick_translate")
            HotkeyAction.LISTEN_TO_TEXT           -> localizer.getString("settings_hotkeys.action_listen")
            HotkeyAction.OPEN_OCR                 -> localizer.getString("settings_hotkeys.action_ocr")
            HotkeyAction.REPLACE_WITH_TRANSLATION -> localizer.getString("settings_hotkeys.action_replace")
            HotkeyAction.CYCLE_TARGET_LANGUAGE    -> localizer.getString("settings_hotkeys.action_cycle_language")
            HotkeyAction.SHOW_DICTIONARY          -> localizer.getString("settings_hotkeys.action_show_dictionary")
            HotkeyAction.TRANSLATE                -> localizer.getString("settings_hotkeys.action_translate")
        }

        val promptLabel = JLabel(
            "<html><center>${localizer.getString("settings_hotkeys.recorder_prompt")}<br><b>$actionName</b></center></html>",
            SwingConstants.CENTER
        ).apply {
            border     = BorderFactory.createEmptyBorder(20, 24, 16, 24)
            alignmentX = Component.CENTER_ALIGNMENT
        }

        val inputField = JTextField(
            if (current?.hasBinding == true) KeyboardPanel.formatBinding(current) else ""
        ).apply {
            font                = font.deriveFont(Font.BOLD, font.size + 4f)
            horizontalAlignment = JTextField.CENTER
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: Color.GRAY),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)
            )
        }

        val hintLabel = JLabel(
            localizer.getString("settings_hotkeys.recorder_hint"),
            SwingConstants.CENTER
        ).apply {
            font       = font.deriveFont(Font.ITALIC, font.size - 1f)
            foreground = UIManager.getColor("Label.disabledForeground")
            border     = BorderFactory.createEmptyBorder(6, 24, 0, 24)
            alignmentX = Component.CENTER_ALIGNMENT
        }

        val okButton     = JButton(localizer.getString("common.ok")).apply { isEnabled = current?.hasBinding ?: false }
        val cancelButton = JButton(localizer.getString("common.cancel"))

        var confirmed = false
        okButton.addActionListener     { confirmed = true;  dialog.dispose() }
        cancelButton.addActionListener { confirmed = false; dialog.dispose() }
        dialog.rootPane.defaultButton = okButton

        inputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                e.consume()

                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    confirmed = false
                    dialog.dispose()
                    return
                }

                if (e.keyCode in setOf(
                        KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT,
                        KeyEvent.VK_ALT, KeyEvent.VK_META, KeyEvent.VK_ALT_GRAPH
                    )) return

                capturedKeyCode   = e.keyCode
                capturedModifiers = e.modifiersEx

                val preview = HotkeyBinding(action = action, keyCode = capturedKeyCode, modifiers = capturedModifiers)
                inputField.text    = KeyboardPanel.formatBinding(preview)
                okButton.isEnabled = true
            }

            override fun keyTyped(e: KeyEvent) { e.consume() }
        })

        val mainPanel = JPanel(BorderLayout(0, 0)).apply {
            border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
            add(promptLabel, BorderLayout.NORTH)
            add(inputField,  BorderLayout.CENTER)
            add(hintLabel,   BorderLayout.SOUTH)
        }

        dialog.contentPane.add(mainPanel, BorderLayout.CENTER)
        dialog.contentPane.add(JPanel(FlowLayout(FlowLayout.TRAILING, 8, 8)).apply {
            add(cancelButton); add(okButton)
        }, BorderLayout.SOUTH)

        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) { inputField.requestFocusInWindow() }
        })

        dialog.pack()
        dialog.minimumSize = Dimension(340, dialog.preferredSize.height)
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true

        if (!confirmed) return null

        return HotkeyBinding(
            action    = action,
            keyCode   = capturedKeyCode,
            modifiers = capturedModifiers,
            isEnabled = current?.isEnabled ?: true,
            scope     = current?.scope ?: HotkeyScope.GLOBAL
        )
    }
}