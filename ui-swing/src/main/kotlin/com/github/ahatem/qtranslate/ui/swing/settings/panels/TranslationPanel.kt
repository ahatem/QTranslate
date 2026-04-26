package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.rewriter.RewriteStyle
import com.github.ahatem.qtranslate.api.summarizer.SummaryLength
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

class TranslationPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager
) : SettingsPanel() {

    private val summaryLengths by lazy {
        listOf(
            SummaryLengthInfo(
                SummaryLength.SHORT,
                localizationManager.getString("settings_translation.summary_length_short")
            ),
            SummaryLengthInfo(
                SummaryLength.MEDIUM,
                localizationManager.getString("settings_translation.summary_length_medium")
            ),
            SummaryLengthInfo(
                SummaryLength.LONG,
                localizationManager.getString("settings_translation.summary_length_long")
            )
        )
    }

    private val rewriteStyles by lazy {
        listOf(
            RewriteStyleInfo(
                RewriteStyle.FORMAL,
                localizationManager.getString("settings_translation.rewrite_style_formal")
            ),
            RewriteStyleInfo(
                RewriteStyle.CASUAL,
                localizationManager.getString("settings_translation.rewrite_style_casual")
            ),
            RewriteStyleInfo(
                RewriteStyle.CONCISE,
                localizationManager.getString("settings_translation.rewrite_style_concise")
            ),
            RewriteStyleInfo(
                RewriteStyle.DETAILED,
                localizationManager.getString("settings_translation.rewrite_style_detailed")
            ),
            RewriteStyleInfo(
                RewriteStyle.SIMPLIFIED,
                localizationManager.getString("settings_translation.rewrite_style_simplified")
            )
        )
    }

    private val types by lazy {
        listOf(
            ExtraOutputTypeInfo(ExtraOutputType.None, localizationManager.getString("settings_translation.type_none")),
            ExtraOutputTypeInfo(
                ExtraOutputType.BackwardTranslate,
                localizationManager.getString("settings_translation.type_backward")
            ),
            ExtraOutputTypeInfo(
                ExtraOutputType.Summarize,
                localizationManager.getString("settings_translation.type_summarize")
            ),
            ExtraOutputTypeInfo(
                ExtraOutputType.Rewrite,
                localizationManager.getString("settings_translation.type_rewrite")
            )
        )
    }

    private lateinit var instantCheck: JCheckBox
    private lateinit var spellCheck: JCheckBox
    private lateinit var removeLineBreaksCheck: JCheckBox
    private lateinit var typeCombo: JComboBox<ExtraOutputTypeInfo>
    private lateinit var useTranslated: JRadioButton
    private lateinit var useInput: JRadioButton

    // Conditional setting rows — shown only for the relevant extra output type
    private lateinit var summaryLengthRow: JPanel
    private lateinit var summaryLengthCombo: JComboBox<SummaryLengthInfo>
    private lateinit var rewriteStyleRow: JPanel
    private lateinit var rewriteStyleCombo: JComboBox<RewriteStyleInfo>

    // Pinned languages — individual JCheckBoxes, NOT a JList.
    //
    // Why not JList with MULTIPLE_INTERVAL_SELECTION?
    // JList.selectedIndices interacts badly with MVI render():
    // applyDraft() → UpdateDraft → state update (async via coroutine) → render()
    // → withoutTrigger { clearSelection(); selectedIndices = [...] }
    // By the time render() runs, isUpdatingFromState is already false (the coroutine
    // arrived after the withoutTrigger block exited), so clearSelection() fires the
    // ListSelectionListener, which calls applyDraft() again, creating a loop that
    // resets the selection to a single item every time.
    //
    // Individual JCheckBoxes have no shared selection model — each one fires its own
    // ActionListener independently. withoutTrigger correctly guards them because the
    // ActionListener checks isUpdatingFromState synchronously in the same call.
    private val languageCheckBoxes = mutableListOf<Pair<String, JCheckBox>>()

    // Translation rules table
    private lateinit var rulesTable: JTable
    private lateinit var rulesTableModel: DefaultTableModel
    private lateinit var removeRuleBtn: JButton

    init {
        buildUI()
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private fun buildUI() {

        // ---- Behaviour ----
        addSeparator(localizationManager.getString("settings_translation.behavior_group"))

        instantCheck = addCheckbox(
            text = localizationManager.getString("settings_translation.instant_translation"),
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(isInstantTranslationEnabled = enabled) } }
        )
        addHint(localizationManager.getString("settings_translation.instant_hint"))

        spellCheck = addCheckbox(
            text = localizationManager.getString("settings_translation.spell_check_input"),
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(isSpellCheckingEnabled = enabled) } }
        )

        removeLineBreaksCheck = addCheckbox(
            text = localizationManager.getString("settings_translation.remove_line_breaks"),
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(isRemoveLineBreaksEnabled = enabled) } }
        )
        addHint(localizationManager.getString("settings_translation.remove_line_breaks_hint"))

        // ---- Extra output ----
        addSeparator(localizationManager.getString("settings_translation.extra_output_group"))

        typeCombo = JComboBox<ExtraOutputTypeInfo>(types.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val type = (selectedItem as? ExtraOutputTypeInfo)?.type ?: return@addActionListener
                    applyDraft(store) { it.copy(extraOutputType = type) }
                }
            }
        }
        addRow(localizationManager.getString("settings_translation.extra_output_type"), typeCombo)
        addHint(localizationManager.getString("settings_translation.extra_output_hint"))

        // ---- Summary length (only visible when type = Summarize) ----
        // Wrapped in a JPanel so we can hide the entire row (label + combo) with one isVisible call.
        // GridBag rows with isVisible=false still occupy space, but since the wrapper
        // fills HORIZONTAL and the inner combo is the only content, it collapses cleanly.
        summaryLengthCombo = JComboBox<SummaryLengthInfo>(summaryLengths.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val length = (selectedItem as? SummaryLengthInfo)?.length ?: return@addActionListener
                    applyDraft(store) { it.copy(summaryLength = length) }
                }
            }
        }
        summaryLengthRow = JPanel(java.awt.BorderLayout(8, 0)).apply {
            isOpaque = false
            isVisible = false  // hidden until type = Summarize
            add(
                JLabel(localizationManager.getString("settings_translation.summary_length")),
                java.awt.BorderLayout.LINE_START
            )
            add(summaryLengthCombo, java.awt.BorderLayout.CENTER)
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0).add(summaryLengthRow)

        // ---- Rewrite style (only visible when type = Rewrite) ----
        rewriteStyleCombo = JComboBox<RewriteStyleInfo>(rewriteStyles.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val style = (selectedItem as? RewriteStyleInfo)?.style ?: return@addActionListener
                    applyDraft(store) { it.copy(rewriteStyle = style) }
                }
            }
        }
        rewriteStyleRow = JPanel(java.awt.BorderLayout(8, 0)).apply {
            isOpaque = false
            isVisible = false  // hidden until type = Rewrite
            add(
                JLabel(localizationManager.getString("settings_translation.rewrite_style")),
                java.awt.BorderLayout.LINE_START
            )
            add(rewriteStyleCombo, java.awt.BorderLayout.CENTER)
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0).add(rewriteStyleRow)

        useTranslated =
            JRadioButton(localizationManager.getString("settings_translation.source_use_translated")).apply {
                addActionListener {
                    if (isSelected && !isUpdatingFromState)
                        applyDraft(store) { it.copy(extraOutputSource = ExtraOutputSource.Output) }
                }
            }
        useInput = JRadioButton(localizationManager.getString("settings_translation.source_use_input")).apply {
            addActionListener {
                if (isSelected && !isUpdatingFromState)
                    applyDraft(store) { it.copy(extraOutputSource = ExtraOutputSource.Input) }
            }
        }
        ButtonGroup().apply { add(useTranslated); add(useInput) }

        val radioPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(useTranslated)
            add(Box.createVerticalStrut(4))
            add(useInput)
        }
        gb.nextRow().add(JLabel(localizationManager.getString("settings_translation.extra_output_source")))
        gb.weightX(1.0).fill(GridBagConstraints.HORIZONTAL).anchor(GridBagConstraints.LINE_START).add(radioPanel)

        // ---- Translation Rules ----
        addSeparator(localizationManager.getString("settings_translation.rules_group"))
        addHint(localizationManager.getString("settings_translation.rules_hint"))

        // Table model — stores raw language codes, renderer shows localized names
        rulesTableModel = object : DefaultTableModel(
            arrayOf(
                localizationManager.getString("settings_translation.rules_col_source"),
                localizationManager.getString("settings_translation.rules_col_target")
            ), 0
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        // Custom renderer — displays localized name but raw code is stored in model
        val localizedRenderer = object : DefaultTableCellRenderer() {
            override fun setValue(value: Any?) {
                text = localizedName(value as? String ?: "")
            }
        }

        rulesTable = JTable(rulesTableModel).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            tableHeader.reorderingAllowed = false
            tableHeader.resizingAllowed = true
            rowHeight = 28
            showHorizontalLines = true
            showVerticalLines = false
            intercellSpacing = Dimension(0, 1)
            fillsViewportHeight = true

            // Apply localized renderer to both columns
            columnModel.getColumn(0).cellRenderer = localizedRenderer
            columnModel.getColumn(1).cellRenderer = localizedRenderer

            // Enable/disable Remove button based on row selection
            selectionModel.addListSelectionListener {
                if (!it.valueIsAdjusting) {
                    removeRuleBtn.isEnabled = selectedRow >= 0
                }
            }
        }

        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 4, 0)
            .add(JScrollPane(rulesTable).apply {
                preferredSize = Dimension(580, 150)
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                border = BorderFactory.createLineBorder(
                    UIManager.getColor("Component.borderColor") ?: Color.GRAY
                )
            })

        // Add Rule / Remove Rule buttons
        val addRuleBtn = JButton(localizationManager.getString("settings_translation.rules_add_btn")).apply {
            addActionListener { showAddRuleDialog() }
        }

        removeRuleBtn = JButton(localizationManager.getString("settings_translation.rules_remove_btn")).apply {
            isEnabled = false // disabled until a row is selected
            addActionListener {
                val selectedRow = rulesTable.selectedRow
                if (selectedRow < 0) return@addActionListener
                // Read raw codes from model — NOT localized display names
                val source = rulesTableModel.getValueAt(selectedRow, 0) as? String ?: return@addActionListener
                val target = rulesTableModel.getValueAt(selectedRow, 1) as? String ?: return@addActionListener
                store.dispatch(SettingsIntent.RemoveTranslationRule(TranslationRule(source, target)))
            }
        }

        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(0, 0, 0, 0)
            .add(JPanel(FlowLayout(FlowLayout.LEADING, 4, 0)).apply {
                isOpaque = false
                add(addRuleBtn)
                add(removeRuleBtn)
            })

        // ---- Pinned languages ----
        addSeparator(localizationManager.getString("settings_translation.pinned_languages_group"))
        addHint(localizationManager.getString("settings_translation.pinned_languages_hint"))

        val checkBoxPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(4, 8, 4, 4)
        }

        COMMON_LANGUAGES.forEach { code ->
            val name = localizedName(code)
            val cb = JCheckBox("$name  ($code)").apply {
                isOpaque = false
                addActionListener {
                    if (!isUpdatingFromState) {
                        // Collect ALL checked codes and save at once
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
                preferredSize = Dimension(580, 200)
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                border = BorderFactory.createLineBorder(
                    UIManager.getColor("Component.borderColor") ?: Color.GRAY
                )
                verticalScrollBar.unitIncrement = 16
            })

        val clearBtn = JButton(localizationManager.getString("settings_translation.pinned_languages_clear")).apply {
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
            g.nextRow().add(JLabel(localizationManager.getString("settings_translation.rules_dialog_source")))
            g.weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(sourceCombo)
            g.nextRow().add(JLabel(localizationManager.getString("settings_translation.rules_dialog_target")))
            g.weightX(1.0).fill(GridBagConstraints.HORIZONTAL).add(targetCombo)
        }

        val result = JOptionPane.showConfirmDialog(
            this,
            panel,
            localizationManager.getString("settings_translation.rules_dialog_title"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )

        if (result != JOptionPane.OK_OPTION) return

        val source = sourceCombo.selectedItem as? String ?: return
        val target = targetCombo.selectedItem as? String ?: return

        if (source == target) {
            JOptionPane.showMessageDialog(
                this,
                localizationManager.getString("settings_translation.rules_error_same"),
                localizationManager.getString("settings_translation.rules_error_same_title"),
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val existing = store.state.value.workingConfiguration.translationRules
        if (existing.any { it.sourceLanguage == source }) {
            JOptionPane.showMessageDialog(
                this,
                localizationManager.getString("settings_translation.rules_error_duplicate", localizedName(source)),
                localizationManager.getString("settings_translation.rules_error_duplicate_title"),
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
            instantCheck.isSelected = c.isInstantTranslationEnabled
            spellCheck.isSelected = c.isSpellCheckingEnabled
            removeLineBreaksCheck.isSelected = c.isRemoveLineBreaksEnabled
            typeCombo.selectedItem = types.find { it.type == c.extraOutputType }

            when (c.extraOutputSource) {
                ExtraOutputSource.Output -> useTranslated.isSelected = true
                ExtraOutputSource.Input -> useInput.isSelected = true
            }
            val extraEnabled = c.extraOutputType != ExtraOutputType.None
            useTranslated.isEnabled = extraEnabled
            useInput.isEnabled = extraEnabled

            // Sync and show/hide conditional settings
            summaryLengthCombo.selectedItem = summaryLengths.find { it.length == c.summaryLength }
            rewriteStyleCombo.selectedItem = rewriteStyles.find { it.style == c.rewriteStyle }
            summaryLengthRow.isVisible = c.extraOutputType == ExtraOutputType.Summarize
            rewriteStyleRow.isVisible = c.extraOutputType == ExtraOutputType.Rewrite

            // Sync translation rules table
            // Store raw codes — renderer handles localized display
            val selectedRow = rulesTable.selectedRow
            rulesTableModel.rowCount = 0
            c.translationRules.forEach { rule ->
                rulesTableModel.addRow(arrayOf(rule.sourceLanguage, rule.targetLanguage))
            }
            // Restore selection if still valid after re-population
            if (selectedRow >= 0 && selectedRow < rulesTableModel.rowCount) {
                rulesTable.setRowSelectionInterval(selectedRow, selectedRow)
            }
            removeRuleBtn.isEnabled = rulesTable.selectedRow >= 0

            // Sync checkboxes — direct assignment, no selection model, no events fired
            // (isUpdatingFromState = true, so ActionListeners are all guarded)
            // Empty pinnedLanguages = no filter active = nothing checked
            languageCheckBoxes.forEach { (code, cb) ->
                cb.isSelected = code in c.pinnedLanguages
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun localizedName(code: String): String {
        val locale = runCatching { java.util.Locale.forLanguageTag(code) }.getOrNull()
        val name = locale?.getDisplayLanguage(java.util.Locale.ENGLISH)
        return if (!name.isNullOrBlank() && name != code) name else code
    }

    private data class ExtraOutputTypeInfo(val type: ExtraOutputType, val displayName: String)
    private data class SummaryLengthInfo(val length: SummaryLength, val displayName: String)
    private data class RewriteStyleInfo(val style: RewriteStyle, val displayName: String)

    companion object {
        private val COMMON_LANGUAGES = LanguageCode.all().toTypedArray()
    }
}

fun LanguageCode.Companion.all(): List<String> = listOf(
    ENGLISH,
    CHINESE_SIMPLIFIED,
    CHINESE_TRADITIONAL,
    HINDI,
    SPANISH,
    FRENCH,
    ARABIC,
    BENGALI,
    RUSSIAN,
    PORTUGUESE,
    INDONESIAN,
    URDU,
    GERMAN,
    JAPANESE,
    SWAHILI,
    MARATHI,
    TELUGU,
    TURKISH,
    TAMIL,
    VIETNAMESE,
    KOREAN,
    ITALIAN,
    THAI,
    GUJARATI,
    JAVANESE,
    FARSI,
    HAUSA,
    BURMESE,
    POLISH,
    UKRAINIAN,
    YORUBA,
    DUTCH,
    GREEK,
    HEBREW,
    HUNGARIAN,
    CZECH,
    SWEDISH,
    ROMANIAN,
    DANISH,
    FINNISH,
    BULGARIAN,
    NORWEGIAN,
    SLOVAK,
    SLOVENIAN,
    CATALAN,
    SERBIAN,
    CROATIAN,
    MALAY,
    NEPALI,
    SINHALA,
    KHMER,
    LAO,
    AMHARIC,
    SOMALI,
    ZULU,
    AFRIKAANS,
    ALBANIAN,
    ARMENIAN,
    AZERBAIJANI,
    BASQUE,
    BELARUSIAN,
    BOSNIAN,
    ESTONIAN,
    GEORGIAN,
    ICELANDIC,
    IRISH,
    LATVIAN,
    LITHUANIAN,
    MACEDONIAN,
    MALTESE,
    MONGOLIAN,
    WELSH
).map { it.tag }