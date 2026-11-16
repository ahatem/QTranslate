package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DisplayValueRenderer
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.LanguageComboBox
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
 * 1. **Defaults** — the source and target languages restored on every launch.
 *
 * 2. **Pinned Languages** — a scrollable checklist of common languages that appear
 *    in the quick-access language picker. Uses individual [JCheckBox]es instead of a
 *    [JList] to avoid the stale-state render loop that multi-select JList triggers
 *    when [withoutTrigger] clears and re-sets the selection.
 *
 * 3. **Translation Rules** — per-source-language forced target overrides displayed
 *    as a two-column table. Add / Remove buttons below.
 *
 * Dictionary auto-lookup used to live here. It is behaviour rather than language
 * configuration, and now sits in [TranslationPanel] with the rest of it.
 */
class LanguagesPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager,
    /**
     * Languages the active translator currently supports.
     * Evaluated at render time so the pickers reflect the translator in use, not whichever one
     * was active when the dialog was built.
     */
    private val availableLanguages: () -> List<LanguageCode> = { emptyList() }
) : SettingsPanel() {

    /** Source may be Auto; the app detects the language in that case. */
    private val defaultSourceCombo = LanguageComboBox(
        onLanguageSelected = { lang ->
            if (!isUpdatingFromState) applyDraft(store) { it.copy(preferredSourceLanguage = lang.tag) }
        },
        localizer = localizationManager
    )

    /** Target may not be Auto: there is nothing to detect a target from. */
    private val defaultTargetCombo = LanguageComboBox(
        onLanguageSelected = { lang ->
            if (!isUpdatingFromState) applyDraft(store) { it.copy(preferredTargetLanguage = lang.tag) }
        },
        localizer = localizationManager
    )

    // Individual checkboxes — see class-level doc for why not JList
    private val languageCheckBoxes = mutableListOf<Pair<String, JCheckBox>>()

    // The list's viewport is sized to whole rows; see buildUI.
    private lateinit var pinnedScroll: JScrollPane
    private var pinnedRowPitch = 0
    private var pinnedContentInset = 0

    // Translation rules table
    private lateinit var rulesTable: JTable
    private lateinit var rulesTableModel: DefaultTableModel
    private lateinit var removeRuleBtn: JButton
    private lateinit var rulesScroll: JScrollPane
    private lateinit var rulesEmptyPanel: JPanel
    private lateinit var rulesEmptyLabel: JLabel

    init { buildUI() }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private fun buildUI() {

        // ---- Defaults ----
        // Moved here from General, where the default target language was the only language
        // setting outside this page. Source sits beside it because a default for one side and
        // not the other is an arbitrary half.
        addSeparator(localizationManager.getString("settings_languages.defaults_group"))
        addRow(
            localizationManager.getString("settings_languages.default_source_language"),
            defaultSourceCombo
        )
        addRow(
            localizationManager.getString("settings_languages.default_target_language"),
            defaultTargetCombo
        )
        addHint(localizationManager.getString("settings_languages.defaults_hint"))

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
            checkBoxPanel.add(Box.createVerticalStrut(PINNED_ROW_GAP))
        }

        // A fixed height that is not a multiple of the row pitch bisects whatever row lands on
        // the boundary: the viewport showed a half-height checkbox at the default size, which
        // reads as a rendering fault rather than as "there is more below". The pitch is measured
        // from the rows themselves (checkbox height plus the strut between them), and the height
        // becomes exactly the number of rows below, so no boundary can fall inside a row. The
        // list still scrolls: the languages outnumber the rows shown.
        pinnedRowPitch = languageCheckBoxes.maxOf { (_, box) -> box.preferredSize.height } + PINNED_ROW_GAP
        pinnedScroll = JScrollPane(checkBoxPanel).apply {
            verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = themeAwareBorder()
            verticalScrollBar.unitIncrement = 16
            // Scroll pane chrome plus the view's own top padding sit above the first row, so
            // they are part of the budget rather than eating into the last one.
            pinnedContentInset = insets.top + insets.bottom +
                viewport.insets.top + viewport.insets.bottom + checkBoxPanel.insets.top
            preferredSize = Dimension(
                UIScale.scale(580),
                pinnedContentInset + PINNED_VISIBLE_ROWS * pinnedRowPitch
            )
        }

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0)
            .add(pinnedScroll)

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
            rowHeight               = UIScale.scale(28)
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

        // An empty grid under two column headings looks like a table that failed to load. This
        // replaces the grid — headings included — with a sentence that says the empty state is the
        // expected one; it is swapped back out as soon as a rule exists.
        rulesEmptyLabel = JLabel(
            localizationManager.getString("settings_languages.rules_empty"),
            SwingConstants.CENTER
        ).apply {
            foreground = UIManager.getColor("Label.disabledForeground")
            border = BorderFactory.createEmptyBorder(
                UIScale.scale(16), UIScale.scale(12), UIScale.scale(16), UIScale.scale(12)
            )
        }
        rulesEmptyPanel = object : JPanel(BorderLayout()), Scrollable {
            init {
                isOpaque = false
                add(rulesEmptyLabel, BorderLayout.CENTER)
            }

            // The message is a state of the same area, not a small component pinned to its corner:
            // fill the viewport so the sentence sits in the middle of the rules box.
            override fun getPreferredScrollableViewportSize(): Dimension =
                Dimension(UIScale.scale(580), UIScale.scale(150))

            override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 16
            override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
                visibleRect.height
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = true
        }

        rulesScroll = JScrollPane(rulesTable).apply {
            preferredSize             = Dimension(580, 150)
            verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = themeAwareBorder()
        }

        gb.nextRow()
            .spanLine()
            .weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 4, 0)
            .add(rulesScroll)

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
        finishLayout()
    }

    // -------------------------------------------------------------------------
    // Add Rule dialog
    // -------------------------------------------------------------------------

    private fun showAddRuleDialog() {
        val sourceCombo = JComboBox<String>(COMMON_LANGUAGES).apply {
            renderer = DisplayValueRenderer<String>(text = { localizedName(it ?: "") })
        }
        val targetCombo = JComboBox<String>(COMMON_LANGUAGES).apply {
            renderer = DisplayValueRenderer<String>(text = { localizedName(it ?: "") })
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
            val hasRules = c.translationRules.isNotEmpty()
            if (hasRules) {
                if (rulesScroll.viewport.view !== rulesTable) {
                    rulesScroll.setColumnHeaderView(rulesTable.tableHeader)
                    rulesScroll.setViewportView(rulesTable)
                }
            } else if (rulesScroll.viewport.view !== rulesEmptyPanel) {
                rulesScroll.setColumnHeaderView(null)
                rulesScroll.setViewportView(rulesEmptyPanel)
            }
            rulesTableModel.rowCount = 0
            c.translationRules.forEach { rule ->
                rulesTableModel.addRow(arrayOf(rule.sourceLanguage, rule.targetLanguage))
            }
            if (selectedRow >= 0 && selectedRow < rulesTableModel.rowCount) {
                rulesTable.setRowSelectionInterval(selectedRow, selectedRow)
            }
            removeRuleBtn.isEnabled = rulesTable.selectedRow >= 0

            // Defaults
            val supported = availableLanguages()
            defaultSourceCombo.render(
                availableLanguages   = supported,
                selectedLanguage     = LanguageCode(c.preferredSourceLanguage),
                autoDetectedLanguage = null,
                isEnabled            = supported.isNotEmpty()
            )
            // AUTO is excluded: a default target must be a concrete language.
            val targets = supported.filter { it.tag != LanguageCode.AUTO.tag }
            defaultTargetCombo.render(
                availableLanguages   = targets,
                selectedLanguage     = LanguageCode(c.preferredTargetLanguage),
                autoDetectedLanguage = null,
                isEnabled            = targets.isNotEmpty()
            )
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun localizedName(code: String): String = settingsPanelLocalizedName(code)

    // ── Test hooks ────────────────────────────────────────────────────────────
    // The pinned list and the rules empty state are geometry and swapped-view facts, not values a
    // render test can read off the store, so they are exposed the way KeyboardPanel exposes its table.

    internal fun pinnedListForTest(): JScrollPane = pinnedScroll
    internal fun pinnedRowPitchForTest(): Int = pinnedRowPitch
    internal fun pinnedContentInsetForTest(): Int = pinnedContentInset
    internal fun rulesScrollForTest(): JScrollPane = rulesScroll
    internal fun rulesTableForTest(): JTable = rulesTable
    internal fun rulesEmptyLabelForTest(): JLabel = rulesEmptyLabel
    internal fun isShowingRulesEmptyState(): Boolean = rulesScroll.viewport.view === rulesEmptyPanel

    companion object {
        val COMMON_LANGUAGES = LanguageCode.all().toTypedArray()

        /** The strut between two checkboxes; part of the row pitch the list is sized to. */
        private const val PINNED_ROW_GAP = 1

        /** Whole rows the pinned list shows before it scrolls. */
        private const val PINNED_VISIBLE_ROWS = 9
    }
}

internal fun settingsPanelLocalizedName(code: String): String =
    runCatching { LanguageCode(code).getDisplayName() }.getOrElse { code }
