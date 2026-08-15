package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Settings panel for language-related configuration.
 *
 * ### Sections
 * 1. **Pinned Languages** — a scrollable checklist of common languages that appear
 *    in the quick-access language picker. Uses individual [JCheckBox]es instead of a
 *    [JList] to avoid the stale-state render loop that multi-select JList triggers
 *    when [withoutTrigger] clears and re-sets the selection.
 *
 * 2. **Translation Rules** — per-source-language forced target overrides displayed
 *    as a two-column table. Add / Remove buttons below.
 *
 * 3. **Dictionary Auto-Lookup** — controls whether the dictionary panel opens
 *    automatically after translation and which text it looks up.
 */
class LanguagesPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager
) : SettingsPanel() {

    private val dictAutoSources by lazy {
        listOf(
            DictionaryAutoSourceInfo(DictionaryAutoSource.OFF,        localizationManager.getString("settings_translation.dict_auto_source_off")),
            DictionaryAutoSourceInfo(DictionaryAutoSource.TRANSLATED, localizationManager.getString("settings_translation.dict_auto_source_translated")),
            DictionaryAutoSourceInfo(DictionaryAutoSource.SOURCE,     localizationManager.getString("settings_translation.dict_auto_source_source")),
        )
    }

    private lateinit var dictAutoSourceCombo: JComboBox<DictionaryAutoSourceInfo>
    private lateinit var dictAutoPopupCheck: JCheckBox

    // Individual checkboxes — see class-level doc for why not JList
    private val languageCheckBoxes = mutableListOf<Pair<String, JCheckBox>>()

    // Translation rules table
    private lateinit var rulesTable: JTable
    private lateinit var rulesTableModel: DefaultTableModel
    private lateinit var removeRuleBtn: JButton

    init { buildUI() }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private fun buildUI() {

        // ---- Pinned Languages ----
        addSeparator(localizationManager.getString("settings_languages.pinned_languages_group"))
        addHint(localizationManager.getString("settings_languages.pinned_languages_hint"))

        val checkBoxPanel = JPanel().apply {
            layout   = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(4, 8, 4, 4)
        }

        COMMON_LANGUAGES.forEach { code ->
            val name = localizedName(code)
            val cb = JCheckBox("$name  ($code)").apply {
                isOpaque = false
                addActionListener {
                    if (!isUpdatingFromState) {
                        val selected = languageCheckBoxes
                            .filter { (_, box) -> box.isSelected }
                            .map { (c, _) -> c }
                        applyDraft(store) { it.copy(pinnedLanguages = selected) }
                    }
                }
            }
            languageCheckBoxes.add(code to cb)
            checkBoxPanel.add(cb)
            checkBoxPanel.add(Box.createVerticalStrut(1))
        }

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0)
            .add(JScrollPane(checkBoxPanel).apply {
                preferredSize = Dimension(UIScale.scale(580), UIScale.scale(200))
                verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                border = themeAwareBorder()
                verticalScrollBar.unitIncrement = 16
            })

        val clearBtn = JButton(localizationManager.getString("settings_languages.pinned_languages_clear")).apply {
            addActionListener {
                languageCheckBoxes.forEach { (_, cb) -> cb.isSelected = false }
                applyDraft(store) { it.copy(pinnedLanguages = emptyList()) }
            }
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0)
            .add(JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
                isOpaque = false
                add(clearBtn)
            })

        // ---- Translation Rules ----
        addSeparator(localizationManager.getString("settings_languages.rules_group"))
        addHint(localizationManager.getString("settings_languages.rules_hint"))

        rulesTableModel = object : DefaultTableModel(
            arrayOf(
                localizationManager.getString("settings_languages.rules_col_source"),
                localizationManager.getString("settings_languages.rules_col_target")
            ), 0
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        val localizedRenderer = object : DefaultTableCellRenderer() {
            override fun setValue(value: Any?) {
                text = localizedName(value as? String ?: "")
            }
        }

        rulesTable = JTable(rulesTableModel).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            tableHeader.reorderingAllowed = false
            tableHeader.resizingAllowed   = true
            rowHeight               = 28
            showHorizontalLines     = true
            showVerticalLines       = false
            intercellSpacing        = Dimension(0, 1)
            fillsViewportHeight     = true
            columnModel.getColumn(0).cellRenderer = localizedRenderer
            columnModel.getColumn(1).cellRenderer = localizedRenderer
            selectionModel.addListSelectionListener {
                if (!it.valueIsAdjusting) removeRuleBtn.isEnabled = selectedRow >= 0
            }
        }

        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 4, 0)
            .add(JScrollPane(rulesTable).apply {
                preferredSize             = Dimension(580, 150)
                verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                border = themeAwareBorder()
            })

        val addRuleBtn = JButton(localizationManager.getString("settings_languages.rules_add_btn")).apply {
            addActionListener { showAddRuleDialog() }
        }
        removeRuleBtn = JButton(localizationManager.getString("settings_languages.rules_remove_btn")).apply {
            isEnabled = false
            addActionListener {
                val row = rulesTable.selectedRow
                if (row < 0) return@addActionListener
                val source = rulesTableModel.getValueAt(row, 0) as? String ?: return@addActionListener
                val target = rulesTableModel.getValueAt(row, 1) as? String ?: return@addActionListener
                store.dispatch(SettingsIntent.RemoveTranslationRule(TranslationRule(source, target)))
            }
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(0, 0, 0, 0)
            .add(JPanel(FlowLayout(FlowLayout.LEADING, 4, 0)).apply {
                isOpaque = false
                add(addRuleBtn)
                add(removeRuleBtn)
            })

        // ---- Dictionary Auto-Lookup ----
        addSeparator(localizationManager.getString("settings_languages.dict_auto_lookup_group"))
        addHint(localizationManager.getString("settings_languages.dict_auto_lookup_hint"))

        dictAutoSourceCombo = JComboBox<DictionaryAutoSourceInfo>(dictAutoSources.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val src = (selectedItem as? DictionaryAutoSourceInfo)?.source ?: return@addActionListener
                    applyDraft(store) { it.copy(dictionaryAutoSource = src) }
                }
            }
        }
        addRow(localizationManager.getString("settings_languages.dict_auto_lookup_source"), dictAutoSourceCombo)

        dictAutoPopupCheck = addCheckbox(
            text     = localizationManager.getString("settings_languages.dict_auto_popup_enabled"),
            selected = true,
            onChange = { enabled -> applyDraft(store) { it.copy(isDictionaryAutoPopupEnabled = enabled) } }
        )

        finishLayout()
    }

    // -------------------------------------------------------------------------
    // Add Rule dialog
    // -------------------------------------------------------------------------

    private fun showAddRuleDialog() {
        val sourceCombo = JComboBox<String>(COMMON_LANGUAGES).apply {
            setRenderer { _, value, _, _, _ -> JLabel(localizedName(value ?: "")) }
        }
        val targetCombo = JComboBox<String>(COMMON_LANGUAGES).apply {
            setRenderer { _, value, _, _, _ -> JLabel(localizedName(value ?: "")) }
        }

        val panel = JPanel(GridBagLayout()).apply {
            val g = GridBag(this, horizontalGap = 8, verticalGap = 8)
            g.nextRow().add(JLabel(localizationManager.getString("settings_languages.rules_dialog_source")))
            g.weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(sourceCombo)
            g.nextRow().add(JLabel(localizationManager.getString("settings_languages.rules_dialog_target")))
            g.weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(targetCombo)
        }

        val result = JOptionPane.showConfirmDialog(
            this,
            panel,
            localizationManager.getString("settings_languages.rules_dialog_title"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )
        if (result != JOptionPane.OK_OPTION) return

        val source = sourceCombo.selectedItem as? String ?: return
        val target = targetCombo.selectedItem as? String ?: return

        if (source == target) {
            JOptionPane.showMessageDialog(
                this,
                localizationManager.getString("settings_languages.rules_error_same"),
                localizationManager.getString("settings_languages.rules_error_same_title"),
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val existing = store.state.value.workingConfiguration.translationRules
        if (existing.any { it.sourceLanguage == source }) {
            JOptionPane.showMessageDialog(
                this,
                localizationManager.getString("settings_languages.rules_error_duplicate", localizedName(source)),
                localizationManager.getString("settings_languages.rules_error_duplicate_title"),
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        store.dispatch(SettingsIntent.AddTranslationRule(TranslationRule(source, target)))
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            // Pinned languages
            languageCheckBoxes.forEach { (code, cb) ->
                cb.isSelected = code in c.pinnedLanguages
            }

            // Translation rules table
            val selectedRow = rulesTable.selectedRow
            rulesTableModel.rowCount = 0
            c.translationRules.forEach { rule ->
                rulesTableModel.addRow(arrayOf(rule.sourceLanguage, rule.targetLanguage))
            }
            if (selectedRow >= 0 && selectedRow < rulesTableModel.rowCount) {
                rulesTable.setRowSelectionInterval(selectedRow, selectedRow)
            }
            removeRuleBtn.isEnabled = rulesTable.selectedRow >= 0

            // Dictionary auto-lookup
            dictAutoSourceCombo.selectedItem = dictAutoSources.find { it.source == c.dictionaryAutoSource }
            dictAutoPopupCheck.isSelected    = c.isDictionaryAutoPopupEnabled
            dictAutoPopupCheck.isEnabled     = c.dictionaryAutoSource != DictionaryAutoSource.OFF
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun localizedName(code: String): String {
        val locale = runCatching { java.util.Locale.forLanguageTag(code) }.getOrNull()
        val name   = locale?.getDisplayLanguage(java.util.Locale.ENGLISH)
        return if (!name.isNullOrBlank() && name != code) name else code
    }

    private data class DictionaryAutoSourceInfo(val source: DictionaryAutoSource, val displayName: String)

    companion object {
        val COMMON_LANGUAGES = LanguageCode.all().toTypedArray()
    }
}
