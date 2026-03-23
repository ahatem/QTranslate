package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

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
        HotkeyAction.OPEN_OCR
    )

    // SHOW_MAIN_WINDOW = double-Ctrl via JNativeHook, cannot be recorded as KeyStroke
    private val nonEditableActions = setOf(HotkeyAction.SHOW_MAIN_WINDOW)

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
                localizationManager.getString("settings_hotkeys.column_hotkey")
            ), 0
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        actionOrder.forEach { action ->
            model.addRow(arrayOf<Any>(actionDisplayName(action), ""))
        }

        table = JTable(model).apply {
            fillsViewportHeight = true
            rowHeight           = 34
            setShowGrid(false)
            intercellSpacing    = Dimension(0, 0)
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            putClientProperty("FlatLaf.style", "showCellFocusIndicator: false")

            columnModel.getColumn(0).apply { preferredWidth = 220; minWidth = 160 }
            columnModel.getColumn(1).apply {
                preferredWidth = 160
                minWidth       = 120
                cellRenderer   = HotkeyColumnRenderer()
            }

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && selectedRow >= 0) onEditSelected()
                }
            })

            selectionModel.addListSelectionListener { updateButtonStates() }
        }

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0)
            .add(JScrollPane(table).apply {
                preferredSize = Dimension(580, actionOrder.size * 34 + 4)
                border = BorderFactory.createLineBorder(
                    UIManager.getColor("Component.borderColor") ?: Color.GRAY
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
            .insets(6, 0, 0, 0)
            .add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(editButton)
                add(clearButton)
                add(resetButton)
            })

        finishLayout()
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private fun onEditSelected() {
        val row    = table.selectedRow.takeIf { it >= 0 } ?: return
        val action = actionOrder[row]
        if (action in nonEditableActions) return

        val current = store.state.value.workingConfiguration
            .hotkeys.find { it.action == action }

        val result = HotkeyRecorderDialog.show(
            owner      = SwingUtilities.getWindowAncestor(this),
            actionName = actionDisplayName(action),
            current    = current,
            localizer  = localizationManager
        ) ?: return  // null = user cancelled

        saveBinding(result)
    }

    private fun onClearSelected() {
        val row    = table.selectedRow.takeIf { it >= 0 } ?: return
        val action = actionOrder[row]
        if (action in nonEditableActions) return
        saveBinding(HotkeyBinding(action = action, keyCode = 0, modifiers = 0))
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

        store.dispatch(SettingsIntent.ToggleSetting {
            it.copy(hotkeys = HotkeyBinding.DEFAULTS)
        })
    }

    private fun saveBinding(binding: HotkeyBinding) {
        store.dispatch(SettingsIntent.ToggleSetting { config ->
            val updated = config.hotkeys.map { b ->
                if (b.action == binding.action) binding else b
            }
            // Add if action wasn't in the list (shouldn't happen with DEFAULTS)
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

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            enableCheck.isSelected = c.isGlobalHotkeysEnabled

            val model = table.model as DefaultTableModel
            actionOrder.forEachIndexed { row, action ->
                val binding = c.hotkeys.find { it.action == action }
                model.setValueAt(binding, row, 1)
            }

            table.isEnabled = c.isGlobalHotkeysEnabled
            updateButtonStates()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun actionDisplayName(action: HotkeyAction): String = when (action) {
        HotkeyAction.SHOW_MAIN_WINDOW     -> localizationManager.getString("settings_hotkeys.action_show_main")
        HotkeyAction.SHOW_QUICK_TRANSLATE -> localizationManager.getString("settings_hotkeys.action_quick_translate")
        HotkeyAction.LISTEN_TO_TEXT       -> localizationManager.getString("settings_hotkeys.action_listen")
        HotkeyAction.OPEN_OCR             -> localizationManager.getString("settings_hotkeys.action_ocr")
    }

    private inner class HotkeyColumnRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.CENTER }

        override fun getTableCellRendererComponent(
            t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
        ): Component {
            super.getTableCellRendererComponent(t, value, sel, focus, row, col)
            val action  = actionOrder.getOrNull(row)
            val binding = value as? HotkeyBinding

            when {
                action in nonEditableActions -> {
                    text       = localizationManager.getString("settings_hotkeys.double_ctrl")
                    foreground = UIManager.getColor("Label.disabledForeground")
                    font       = font.deriveFont(Font.ITALIC)
                }
                binding == null || !binding.hasBinding -> {
                    text       = localizationManager.getString("settings_hotkeys.no_binding")
                    foreground = UIManager.getColor("Label.disabledForeground")
                    font       = font.deriveFont(Font.PLAIN)
                }
                else -> {
                    text       = formatBinding(binding)
                    foreground = if (sel) UIManager.getColor("Table.selectionForeground")
                    else     UIManager.getColor("Table.foreground")
                    font       = font.deriveFont(Font.BOLD)
                }
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

// =============================================================================
// HotkeyRecorderDialog
// =============================================================================

object HotkeyRecorderDialog {

    fun show(
        owner: Window?,
        actionName: String,
        current: HotkeyBinding?,
        localizer: LocalizationManager
    ): HotkeyBinding? {

        var capturedKeyCode  = current?.keyCode  ?: 0
        var capturedModifiers = current?.modifiers ?: 0
        var hasCapture       = current?.hasBinding ?: false

        val action = current?.action ?: return null

        val dialog = JDialog(
            owner,
            localizer.getString("settings_hotkeys.recorder_title"),
            Dialog.ModalityType.APPLICATION_MODAL
        )
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

        // ---- Labels ----
        val promptLabel = JLabel(
            "<html><center>${localizer.getString("settings_hotkeys.recorder_prompt")}<br>" +
                    "<b>$actionName</b></center></html>",
            SwingConstants.CENTER
        ).apply {
            border     = BorderFactory.createEmptyBorder(20, 24, 16, 24)
            alignmentX = Component.CENTER_ALIGNMENT
        }

        // ---- Input field — the capture sink ----
        val inputField = JTextField(
            if (current?.hasBinding == true) KeyboardPanel.formatBinding(current) else ""
        ).apply {
            font                = font.deriveFont(Font.BOLD, font.size + 4f)
            horizontalAlignment = JTextField.CENTER
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                    UIManager.getColor("Component.borderColor") ?: Color.GRAY
                ),
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

        // ---- Buttons ----
        val okButton = JButton(localizer.getString("common.ok")).apply {
            isEnabled = current?.hasBinding ?: false
        }
        val cancelButton = JButton(localizer.getString("common.cancel"))

        var confirmed = false
        okButton.addActionListener     { confirmed = true;  dialog.dispose() }
        cancelButton.addActionListener { confirmed = false; dialog.dispose() }
        dialog.rootPane.defaultButton = okButton

        // ---- Key capture ----
        inputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // Consume immediately — prevent any text from appearing
                e.consume()

                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    confirmed = false
                    dialog.dispose()
                    return
                }

                // Ignore bare modifier presses — need an actual key
                if (e.keyCode in setOf(
                        KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT,
                        KeyEvent.VK_ALT, KeyEvent.VK_META,
                        KeyEvent.VK_ALT_GRAPH
                    )) return

                // Store raw integers — no string conversion at all
                capturedKeyCode   = e.keyCode
                capturedModifiers = e.modifiersEx
                hasCapture        = true

                // Build a temporary binding just for display
                val preview = HotkeyBinding(
                    action    = action,
                    keyCode   = capturedKeyCode,
                    modifiers = capturedModifiers
                )
                inputField.text    = KeyboardPanel.formatBinding(preview)
                okButton.isEnabled = true
            }

            // Swallow keyTyped so no character ever gets inserted
            override fun keyTyped(e: KeyEvent) { e.consume() }
        })

        // ---- Layout ----
        val mainPanel = JPanel(BorderLayout(0, 0)).apply {
            border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
            add(promptLabel, BorderLayout.NORTH)
            add(inputField,  BorderLayout.CENTER)
            add(hintLabel,   BorderLayout.SOUTH)
        }

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8)).apply {
            add(cancelButton)
            add(okButton)
        }

        dialog.contentPane.add(mainPanel,     BorderLayout.CENTER)
        dialog.contentPane.add(buttonsPanel,  BorderLayout.SOUTH)

        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                inputField.requestFocusInWindow()
            }
        })

        dialog.pack()
        dialog.minimumSize = Dimension(340, dialog.preferredSize.height)
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true  // blocks until disposed

        if (!confirmed) return null

        return HotkeyBinding(
            action    = action,
            keyCode   = capturedKeyCode,
            modifiers = capturedModifiers,
            isEnabled = true
        )
    }
}