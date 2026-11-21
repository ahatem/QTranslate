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
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

    /** The column of pin checkboxes; rebuilt when the active translator's languages change. */
    private lateinit var pinnedListPanel: JPanel

    /** Which languages the current checklist was built from, so it is not rebuilt every render. */
    private var pinnedSignature: List<String> = emptyList()

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

        // A grid of tiles, not a column of checkboxes. Pinning a language is picking it out of a
        // set, not reading a list down — and a grid shows a dozen of them at once where the column
        // showed nine before it scrolled. The wrapping layout is what lets the number of columns
        // follow the page's width, so the same grid reads well at 100% and at 200%.
        val tileGrid = JPanel(
            WrapLayout(FlowLayout.LEADING, UIScale.scale(PINNED_TILE_GAP), UIScale.scale(PINNED_TILE_GAP))
        ).apply {
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }
        pinnedListPanel = tileGrid

        // The rows come from the active translator's own languages rather than from a hard-coded
        // list: a language a plugin introduced can be pinned, and one the translator does not
        // support is not offered as if it were.
        rebuildPinnedList(
            pinCodes(store.state.value.workingConfiguration.pinnedLanguages)
        )

        // The list is sized in whole rows of tiles, so the viewport never ends halfway through one:
        // a half tile reads as a rendering fault rather than as "there is more below". The pitch is
        // measured from a real tile rather than assumed, so it follows the zoom and the theme's font.
        pinnedRowPitch = (tileGrid.components.filterIsInstance<JComponent>()
            .maxOfOrNull { it.preferredSize.height } ?: UIScale.scale(34)) + UIScale.scale(PINNED_TILE_GAP)
        pinnedScroll = JScrollPane(tileGrid).apply {
            verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = themeAwareBorder()
            verticalScrollBar.unitIncrement = 16
            // Scroll pane chrome plus the grid's own padding sit above the first row of tiles, so
            // they are part of the budget rather than eating into the last row.
            pinnedContentInset = insets.top + insets.bottom +
                viewport.insets.top + viewport.insets.bottom + tileGrid.insets.top + tileGrid.insets.bottom
            preferredSize = Dimension(
                UIScale.scale(580),
                pinnedContentInset + PINNED_VISIBLE_ROWS * pinnedRowPitch
            )
        }

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0)
            .add(pinnedScroll)

        // The tile width is a share of this pane, so it is recomputed when the pane changes size:
        // the dialog's own divider, a zoom change, or a resize of the window.
        pinnedScroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = equaliseTiles()
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
        // An action is searched for by its own words too: someone who remembers "add rule" is
        // looking for this button, and a search that only indexes settings would not find it.
        registerSearchEntry(localizationManager.getString("settings_languages.rules_add_btn"), addRuleBtn)
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
        val sourceCombo = JComboBox<String>(ruleLanguageCodes().toTypedArray()).apply {
            renderer = DisplayValueRenderer<String>(text = { localizedName(it ?: "") })
        }
        val targetCombo = JComboBox<String>(ruleLanguageCodes().toTypedArray()).apply {
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
            // Pinned languages. The rows follow the active translator, so they are rebuilt when
            // that set changes. A pinned language it does not advertise stays visible and checked:
            // silently dropping the row would silently drop the stored pin on the next save.
            val codes = pinCodes(c.pinnedLanguages)
            if (codes != pinnedSignature) {
                pinnedSignature = codes
                rebuildPinnedList(codes)
            }
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

    /**
     * The languages the pin list shows: the active translator's own, then any pin it does not
     * advertise, so a stored pin is never hidden from the user who set it.
     */
    private fun pinCodes(pinned: List<String>): List<String> {
        val supported = availableLanguages()
            .map { it.tag }
            .filter { it != LanguageCode.AUTO.tag }
        return supported + pinned.filter { it !in supported }
    }

    /** Rebuilds the pin tiles, keeping the saved selection. */
    private fun rebuildPinnedList(codes: List<String>) {
        val pinned = store.state.value.workingConfiguration.pinnedLanguages.toSet()
        val supported = availableLanguages().map { it.tag }.toSet()
        pinnedListPanel.removeAll()
        languageCheckBoxes.clear()
        codes.forEach { code ->
            val checkBox = JCheckBox().apply {
                isOpaque = false
                isSelected = code in pinned
                addActionListener {
                    if (!isUpdatingFromState) {
                        val selected = languageCheckBoxes
                            .filter { (_, box) -> box.isSelected }
                            .map { (c, _) -> c }
                        applyDraft(store) { it.copy(pinnedLanguages = selected) }
                    }
                }
            }
            languageCheckBoxes.add(code to checkBox)
            pinnedListPanel.add(PinnedTile(code, checkBox, available = code in supported))
        }

        equaliseTiles()

        pinnedListPanel.revalidate()
        pinnedListPanel.repaint()
    }

    /**
     * Gives every tile the same width, and the width to the row.
     *
     * Equal cells are what makes a grid readable — the tags line up, the eye can skip down a column
     * — but sizing them from the widest *name* in the set makes one long language widen every tile
     * and drops the grid to two fat columns. So the width comes from the viewport instead: as many
     * columns as fit at a comfortable tile width, then every tile the same share of the row. It
     * runs again whenever the viewport resizes, which is what keeps the grid aligned after a drag of
     * the dialog's own divider or a change of zoom.
     */
    private fun equaliseTiles() {
        val tiles = pinnedListPanel.components.filterIsInstance<PinnedTile>()
        if (tiles.isEmpty()) return

        val gap = UIScale.scale(PINNED_TILE_GAP)
        val insets = pinnedListPanel.insets
        val viewportWidth = if (::pinnedScroll.isInitialized) pinnedScroll.viewport.extentSize.width else 0

        val width = if (viewportWidth > 0) {
            val usable = viewportWidth - insets.left - insets.right - gap * 2
            val columns = (usable / (UIScale.scale(PINNED_TILE_MIN_WIDTH) + gap)).coerceIn(2, 5)
            (usable - gap * (columns - 1)) / columns
        } else {
            // Before the pane has a size: as wide as the widest tile wants to be, so the first
            // layout is not one tile per row.
            tiles.maxOf { it.preferredSize.width }
        }
        if (width <= 0) return

        val height = tiles.maxOf { it.preferredSize.height }
        var changed = false
        tiles.forEach { tile ->
            val size = Dimension(width, height)
            if (tile.preferredSize != size || tile.minimumSize != size) {
                tile.preferredSize = size
                tile.minimumSize   = size
                changed = true
            }
        }
        if (changed) pinnedListPanel.revalidate()
    }

    /**
     * One language as a tile in the pin grid.
     *
     * The language is a choice, so the whole tile is the target rather than the 12-pixel box: the
     * checkbox is the state carrier — which is what the render path and the clear action read — and
     * the tile forwards its own clicks to it.
     *
     * Every colour is read from the look and feel at paint time. A chosen tile takes the accent's
     * own tint with an accent border; an untouched one keeps the ordinary border every other
     * surface in this dialog has; hover takes the inactive selection colour the lists use. That
     * way a theme change needs no work here, and a custom accent recolours the grid with it.
     */
    private inner class PinnedTile(
        code: String,
        private val checkBox: JCheckBox,
        available: Boolean,
    ) : JPanel(BorderLayout(UIScale.scale(8), 0)) {

        private var hovered = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = BorderFactory.createEmptyBorder(
                UIScale.scale(7), UIScale.scale(10), UIScale.scale(7), UIScale.scale(12)
            )

            val name = JLabel(localizedName(code)).apply {
                font = font.deriveFont(Font.BOLD)
                if (!available) {
                    // Offered and checkable all the same: a stored pin the active translator does
                    // not support is kept, and dropping its tile would drop the pin on the next save.
                    foreground = UIManager.getColor("Label.disabledForeground")
                    toolTipText = localizationManager.getString("settings_languages.pin_unavailable")
                }
            }
            val tag = JLabel(code).apply {
                font = font.deriveFont(font.size - 1f)
                foreground = UIManager.getColor("Label.disabledForeground")
            }
            val labels = JPanel(BorderLayout(UIScale.scale(8), 0)).apply {
                isOpaque = false
                add(name, BorderLayout.CENTER)
                add(tag,  BorderLayout.LINE_END)
            }

            add(checkBox, BorderLayout.LINE_START)
            add(labels,   BorderLayout.CENTER)

            val toggle = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = checkBox.doClick()
                override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                override fun mouseExited(e: MouseEvent)  { hovered = false; repaint() }
            }
            listOf(this, name, tag, labels, checkBox).forEach { it.addMouseListener(toggle) }
        }

        override fun paintComponent(g: Graphics) {
            val accent = UIManager.getColor("Component.accentColor") ?: SettingsVisuals.accent()
            val base   = SettingsVisuals.background()
            val fill = when {
                checkBox.isSelected -> SettingsVisuals.blend(accent, base, 0.82f)
                hovered -> UIManager.getColor("List.selectionInactiveBackground") ?: base
                else -> base
            }

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = UIScale.scale(9)
                g2.color = fill
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = if (checkBox.isSelected) accent
                else UIManager.getColor("Component.borderColor") ?: accent
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /**
     * Languages a rule may name: the translator's own, plus any language an existing rule already
     * uses. Restricting the list to what the translator supports is what makes a rule's target a
     * real choice; keeping the used ones means an older rule can still be read and removed.
     */
    private fun ruleLanguageCodes(): List<String> {
        val supported = availableLanguages().map { it.tag }.filter { it != LanguageCode.AUTO.tag }
        val used = store.state.value.workingConfiguration.translationRules
            .flatMap { listOf(it.sourceLanguage, it.targetLanguage) }
            .filter { it !in supported }
        return (supported + used).distinct()
    }

    private fun localizedName(code: String): String = settingsPanelLocalizedName(code)

    // ── Test hooks ────────────────────────────────────────────────────────────
    // The pinned list and the rules empty state are geometry and swapped-view facts, not values a
    // render test can read off the store, so they are exposed the way KeyboardPanel exposes its table.

    internal fun pinnedListForTest(): JScrollPane = pinnedScroll
    internal fun pinnedCodesForTest(): List<String> = languageCheckBoxes.map { it.first }
    internal fun pinnedRowPitchForTest(): Int = pinnedRowPitch
    internal fun pinnedContentInsetForTest(): Int = pinnedContentInset
    internal fun rulesScrollForTest(): JScrollPane = rulesScroll
    internal fun rulesTableForTest(): JTable = rulesTable
    internal fun rulesEmptyLabelForTest(): JLabel = rulesEmptyLabel
    internal fun isShowingRulesEmptyState(): Boolean = rulesScroll.viewport.view === rulesEmptyPanel

    companion object {
        /** Gap between tiles, across a row and between rows; part of the pitch the grid is sized to. */
        private const val PINNED_TILE_GAP = 6

        /** The comfortable width a tile is fitted to; the grid takes as many columns as fit at it. */
        private const val PINNED_TILE_MIN_WIDTH = 130

        /** Whole rows of tiles the grid shows before it scrolls. */
        private const val PINNED_VISIBLE_ROWS = 3
    }
}

internal fun settingsPanelLocalizedName(code: String): String =
    runCatching { LanguageCode(code).getDisplayName() }.getOrElse { code }
