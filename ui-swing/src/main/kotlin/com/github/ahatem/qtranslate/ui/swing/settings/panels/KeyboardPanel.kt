package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import java.awt.Dimension
import java.awt.GridBagConstraints
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class KeyboardPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager
) : SettingsPanel() {

    private lateinit var enableCheck: JCheckBox
    private lateinit var table: JTable

    init {
        buildUI()
    }

    private fun buildUI() {
        addSeparator(localizationManager.getString("settings_hotkeys.global_group"))

        enableCheck = addCheckbox(
            text = localizationManager.getString("settings_hotkeys.enable_global"),
            selected = false,
            onChange = { enabled ->
                applyDraft(store) { it.copy(isGlobalHotkeysEnabled = enabled) }
            }
        )

        addSeparator(localizationManager.getString("settings_hotkeys.assignments_group"))

        val model = object : DefaultTableModel(
            arrayOf(
                localizationManager.getString("settings_hotkeys.column_action"),
                localizationManager.getString("settings_hotkeys.column_hotkey"),
                localizationManager.getString("settings_hotkeys.column_description")
            ),
            0
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        model.addRow(
            arrayOf(
                localizationManager.getString("settings_hotkeys.action_show_main"),
                "Ctrl + Ctrl",
                localizationManager.getString("settings_hotkeys.desc_show_main")
            )
        )
        model.addRow(
            arrayOf(
                localizationManager.getString("settings_hotkeys.action_quick_translate"),
                "Ctrl + Q",
                localizationManager.getString("settings_hotkeys.desc_quick_translate")
            )
        )
        model.addRow(
            arrayOf(
                localizationManager.getString("settings_hotkeys.action_listen"),
                "Ctrl + E",
                localizationManager.getString("settings_hotkeys.desc_listen")
            )
        )
        model.addRow(
            arrayOf(
                localizationManager.getString("settings_hotkeys.action_ocr"),
                "Ctrl + I",
                localizationManager.getString("settings_hotkeys.desc_ocr")
            )
        )

        table = JTable(model).apply {
            fillsViewportHeight = true
            rowHeight = 30
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            putClientProperty("FlatLaf.style", "showCellFocusIndicator: false")

            columnModel.getColumn(0).apply { preferredWidth = 160; minWidth = 120 }
            columnModel.getColumn(1).apply {
                preferredWidth = 110
                minWidth = 90
                cellRenderer = object : DefaultTableCellRenderer() {
                    init {
                        horizontalAlignment = SwingConstants.CENTER
                    }
                }
            }
            columnModel.getColumn(2).apply { preferredWidth = 300; minWidth = 200 }
        }

        val scrollPane = JScrollPane(table).apply {
            preferredSize = Dimension(650, 148)
            border = BorderFactory.createLineBorder(
                UIManager.getColor("Component.borderColor") ?: java.awt.Color.GRAY
            )
        }

        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .weightY(0.3)
            .fill(GridBagConstraints.BOTH)
            .insets(4, 0, 0, 0)
            .add(scrollPane)

        addHint(localizationManager.getString("settings_hotkeys.customization_hint"))

        finishLayout()
    }

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            enableCheck.isSelected = c.isGlobalHotkeysEnabled
            table.isEnabled = c.isGlobalHotkeysEnabled
            table.alpha(if (c.isGlobalHotkeysEnabled) 1.0f else 0.5f)
        }
    }

    private fun JTable.alpha(value: Float) {
        foreground = foreground.withAlpha(value)
    }

    private fun java.awt.Color.withAlpha(factor: Float): java.awt.Color {
        val a = (255 * factor.coerceIn(0f, 1f)).toInt()
        return java.awt.Color(red, green, blue, a)
    }
}