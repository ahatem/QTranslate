package com.github.ahatem.qtranslate.ui.swing.settings

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LanguageFileMeta
import com.github.ahatem.qtranslate.core.localization.LanguageFileWriter
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * Edits a translation file: its strings, its metadata, and whether it exists at all.
 *
 * ### Why a dialog rather than a settings page
 * Translating an interface is a job of work, not a preference. It wants the width for three
 * columns of text and a session of its own, and it does not belong in a list of switches beside
 * the theme picker.
 *
 * ### What it writes
 * Files go to the user's own languages directory, the one the application already reads, so an
 * edit is live as soon as it is saved and the file is theirs to send on or open a pull request
 * with. Nothing here touches the repository.
 *
 * The English file is the template for the output, so a saved translation reads like a
 * hand-written one and a diff against English shows only the translating. See
 * [LanguageFileWriter].
 */
class LanguageEditorDialog(
    owner: JDialog,
    private val localizationManager: LocalizationManager,
    private val scope: CoroutineScope,
    /**
     * Translates one string, for the per-row suggestion. Absent when no translator is configured,
     * which hides the action rather than offering something that cannot work.
     */
    private val translateString: (suspend (String, LanguageCode) -> Result<String>)? = null,
    /**
     * The language to open on, or null to start a new one.
     *
     * The dialog is reached from a specific language's row, so it opens on that language and says
     * so in its title. Opening on a list of every translation made people think they had pressed
     * the wrong thing: they asked to edit the language in front of them and met a manager.
     */
    private val initialCode: String? = null
) : JDialog(owner, true) {

    private val parser = LanguageTomlParser()
    private val english = localizationManager.englishStrings()

    private val rows = english.map { (key, value) -> Row(key, value, "") }
    private val model = StringsModel(
        rows,
        listOf("", text("col_key"), text("col_english"), text("col_translation")),
        text("placeholder_mismatch")
    )
    private val table = JTable(model)
    private val sorter = TableRowSorter(model)

    private val searchField = JTextField()
    private val untranslatedOnly = JCheckBox(text("only_untranslated"))
    private val coverageLabel = JLabel()

    private val nameField = JTextField()
    private val nativeNameField = JTextField()
    private val localeField = JTextField()
    private val translatorsField = JTextField()
    private val rtlCheck = JCheckBox(text("rtl"))

    private val detailsPanel = JPanel(GridBagLayout()).apply { isOpaque = false }
    private val detailsSummary = JLabel()
    private val detailsToggle = JButton()

    private var loadedCode: String? = null
    private var dirty = false

    init {
        title = text("title")
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        layout = BorderLayout()
        minimumSize = Dimension(UIScale.scale(820), UIScale.scale(560))
        preferredSize = Dimension(UIScale.scale(980), UIScale.scale(680))

        add(buildHeader(), BorderLayout.NORTH)
        add(buildTable(), BorderLayout.CENTER)
        add(buildFooter(), BorderLayout.SOUTH)

        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent?) = closeWithGuard()
        })
        rootPane.registerKeyboardAction(
            { closeWithGuard() },
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        if (initialCode == null) createLanguage() else loadLanguage(initialCode)
        pack()
        // A dialog does not inherit orientation from the window that opened it, and the default
        // reads as left-to-right. Without this the editor was the one window in the application
        // that stayed unmirrored in Arabic — including for someone editing the Arabic translation.
        applyComponentOrientation(owner.componentOrientation)
        setLocationRelativeTo(owner)
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildHeader(): JComponent {
        val details = detailsPanel.apply {
            val c = GridBagConstraints().apply {
                insets = Insets(2, 0, 2, 8); anchor = GridBagConstraints.LINE_START
                fill = GridBagConstraints.HORIZONTAL
            }
            fun field(label: String, comp: JComponent, x: Int, y: Int, weight: Double) {
                c.gridx = x; c.gridy = y; c.weightx = 0.0
                add(JLabel(text(label)), c)
                c.gridx = x + 1; c.weightx = weight
                add(comp, c)
            }
            field("name", nameField, 0, 0, 0.5)
            field("native_name", nativeNameField, 2, 0, 0.5)
            field("locale", localeField, 0, 1, 0.5)
            field("translators", translatorsField, 2, 1, 0.5)
            c.gridx = 0; c.gridy = 2; c.gridwidth = 4; c.weightx = 1.0
            add(rtlCheck, c)
        }
        listOf(nameField, nativeNameField, localeField, translatorsField).forEach { it.onEdit { markDirty() } }
        rtlCheck.addActionListener { markDirty() }
        translatorsField.putClientProperty(
            FlatClientProperties.PLACEHOLDER_TEXT, text("translators_placeholder")
        )

        val filters = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createEmptyBorder(8, 0, 4, 0)
            searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, text("search"))
            searchField.onEdit { applyFilter() }
            untranslatedOnly.addActionListener { applyFilter() }
            add(searchField, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, 8, 0)).apply {
                isOpaque = false
                add(untranslatedOnly)
                add(coverageLabel.apply { font = font.deriveFont(Font.BOLD) })
            }, BorderLayout.LINE_END)
        }

        // Closed by default. These five fields are set once when a translation is started and
        // never touched again, and open they took roughly a fifth of a window whose real content
        // is 546 rows the user will scroll for an hour. Closing them buys four more rows a screen.
        details.isVisible = false
        val summary = JPanel(BorderLayout(UIScale.scale(8), 0)).apply {
            isOpaque = false
            add(detailsSummary, BorderLayout.CENTER)
            add(detailsToggle.apply {
                text = detailsToggleLabel()
                putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
                addActionListener {
                    details.isVisible = !details.isVisible
                    text = detailsToggleLabel()
                    revalidate()
                }
            }, BorderLayout.LINE_END)
        }

        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 0, 12)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(summary, BorderLayout.NORTH)
                add(details, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(filters, BorderLayout.SOUTH)
        }
    }

    private fun detailsToggleLabel() =
        text(if (detailsPanel.isVisible) "hide_details" else "show_details")

    /** One line standing in for the closed metadata block, so nothing is hidden without trace. */
    private fun updateDetailsSummary() {
        val parts = listOfNotNull(
            nameField.text.trim().takeIf { it.isNotEmpty() },
            localeField.text.trim().takeIf { it.isNotEmpty() },
            translatorsField.text.trim().takeIf { it.isNotEmpty() },
            text(if (rtlCheck.isSelected) "dir_rtl" else "dir_ltr")
        )
        detailsSummary.text = parts.joinToString("  ·  ")
    }

    private fun buildTable(): JComponent {
        table.apply {
            rowSorter = sorter
            rowHeight = UIScale.scale(26)
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            fillsViewportHeight = true
            putClientProperty(FlatClientProperties.STYLE, "showHorizontalLines: true")
            columnModel.getColumn(StringsModel.COL_DONE).apply {
                val width = UIScale.scale(26)
                preferredWidth = width; minWidth = width; maxWidth = width
                cellRenderer = DoneRenderer(this@LanguageEditorDialog.model)
            }
            columnModel.getColumn(StringsModel.COL_KEY).preferredWidth = UIScale.scale(210)
            columnModel.getColumn(StringsModel.COL_ENGLISH).preferredWidth = UIScale.scale(290)
            columnModel.getColumn(StringsModel.COL_TRANSLATION).preferredWidth = UIScale.scale(310)
            // Sorting would destroy the declaration order the file's structure depends on, and
            // there is no way back from it.
            (0 until columnCount).forEach { sorter.setSortable(it, false) }
            // A single click starts editing: this is a form to fill in, and making someone
            // double-click 159 times to do it is the difference between finishable and not.
            (getDefaultEditor(String::class.java) as? DefaultCellEditor)?.clickCountToStart = 1
            setDefaultRenderer(
                String::class.java,
                UntranslatedAwareRenderer(this@LanguageEditorDialog.model)
            )
        }
        model.addTableModelListener { if (it.column == StringsModel.COL_TRANSLATION) markDirty() }

        return JScrollPane(table).apply {
            border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
        }
    }

    private fun buildFooter(): JComponent {
        val actions = JPanel(FlowLayout(FlowLayout.LEADING, 6, 0)).apply {
            if (translateString != null) {
                add(JButton(text("suggest")).apply {
                    toolTipText = text("suggest_tooltip")
                    addActionListener { suggestForSelection() }
                })
            }
            add(JButton(text("reset_row")).apply {
                toolTipText = text("reset_row_tooltip")
                addActionListener { resetSelection() }
            })
        }
        // Deliberately far from Save. It destroys the file, and one slip beside the button people
        // reach for constantly is how that happens.
        actions.add(Box.createHorizontalStrut(UIScale.scale(16)))
        actions.add(JButton(text("delete")).apply {
            foreground = UIManager.getColor("Component.error.focusedBorderColor") ?: foreground
            addActionListener { deleteLanguage() }
        })

        val saveButton = JButton(text("save")).apply { addActionListener { save() } }
        // Enter saves, which is what someone who has just typed a translation expects. Set on the
        // dialog's own root pane, and only once there is one: a button that is not yet in a
        // hierarchy has no root pane, and reaching for it through the button threw before the
        // dialog could open at all.
        SwingUtilities.invokeLater { rootPane.defaultButton = saveButton }

        val buttons = JPanel(FlowLayout(FlowLayout.TRAILING, 6, 0)).apply {
            add(JButton(text("close")).apply { addActionListener { closeWithGuard() } })
            add(saveButton)
        }
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(10, 12, 12, 12)
            add(actions, BorderLayout.LINE_START)
            add(buttons, BorderLayout.LINE_END)
        }
    }

    // ── Loading and saving ────────────────────────────────────────────────────


    private fun loadLanguage(code: String) {
        scope.launch {
            val file = File(localizationManager.languagesDirectory, "$code.toml")
            // Three outcomes, not two. A file that is absent is a translation nobody has started,
            // and editing it is the point. A file that is present but unreadable is a translation
            // that already exists, and treating that as empty is how saving destroyed it: the
            // writer omits untranslated keys, and the save is a full overwrite.
            val existed = withContext(Dispatchers.IO) { file.exists() }
            val parsed = withContext(Dispatchers.IO) {
                if (!existed) null else runCatching { parser.parse(file.readText()) }.getOrNull()
            }
            if (existed && parsed == null) {
                withContext(Dispatchers.Swing) { showUnreadable(code, file) }
                return@launch
            }
            withContext(Dispatchers.Swing) {
                loadedCode = code
                val meta = parsed?.meta
                // Names the language being edited, so the dialog answers the question the user
                // arrived with rather than presenting itself as a manager of all of them.
                title = text("title_for", meta?.name ?: code, code)
                nameField.text = meta?.name.orEmpty()
                nativeNameField.text = meta?.nativeName.orEmpty()
                localeField.text = meta?.locale ?: code
                translatorsField.text = meta?.translators?.joinToString(", ").orEmpty()
                rtlCheck.isSelected = meta?.isRtl == true
                model.replaceTranslations(parsed?.entries.orEmpty())
                setEnabledForContent(true)
                dirty = false
                updateCoverage()
                updateDetailsSummary()
                // A translation being started has nothing to summarise and every field to fill,
                // so it opens with them showing; an existing one opens on its strings.
                detailsPanel.isVisible = parsed == null
                detailsToggle.text = detailsToggleLabel()
            }
        }
    }

    /**
     * Refuses to edit a translation that could not be read.
     *
     * Opening it blank would look exactly like an unstarted translation, and one press of Save
     * would then replace a complete file with an empty one. Closing is the only safe answer the
     * editor can give without understanding what is wrong with the file.
     */
    private fun showUnreadable(code: String, file: File) {
        JOptionPane.showMessageDialog(
            this,
            text("unreadable", code, file.absolutePath),
            text("unreadable_title"),
            JOptionPane.ERROR_MESSAGE
        )
        dirty = false
        dispose()
    }

    private fun save() {
        val code = loadedCode ?: return

        // Warned about rather than blocked. A mismatch is nearly always a mistake, but the person
        // editing knows their language better than this check does, and refusing the save would
        // trap work that is otherwise finished.
        val mismatched = model.mismatches()
        if (mismatched.isNotEmpty()) {
            val proceed = JOptionPane.showConfirmDialog(
                this,
                text("mismatch_warning", mismatched.size),
                text("unreadable_title"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (proceed != JOptionPane.OK_OPTION) return
        }
        val meta = LanguageFileMeta(
            name = nameField.text.trim(),
            nativeName = nativeNameField.text.trim().ifBlank { nameField.text.trim() },
            locale = localeField.text.trim().ifBlank { code },
            translators = translatorsField.text.split(',')
                .map { it.trim().removePrefix("@") }
                .filter { it.isNotEmpty() },
            isRtl = rtlCheck.isSelected
        )
        scope.launch {
            val body = LanguageFileWriter(localizationManager.englishTemplate())
                .write(meta, model.translations())
            withContext(Dispatchers.IO) {
                File(localizationManager.languagesDirectory, "$code.toml").writeText(body)
            }
            withContext(Dispatchers.Swing) {
                dirty = false
                localizationManager.forget(LanguageCode(code))
                updateCoverage()
                JOptionPane.showMessageDialog(
                    this@LanguageEditorDialog, text("saved"), title, JOptionPane.INFORMATION_MESSAGE
                )
            }
        }
    }

    private fun createLanguage() {
        val code = JOptionPane.showInputDialog(this, text("new_prompt"), text("new_title"), JOptionPane.QUESTION_MESSAGE)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            // Cancelled before anything existed, so there is nothing to edit and no reason to
            // leave an empty editor on screen.
            ?: return run { if (loadedCode == null) dispose() }

        val file = File(localizationManager.languagesDirectory, "$code.toml")
        if (file.exists()) {
            JOptionPane.showMessageDialog(this, text("new_exists"), text("new_title"), JOptionPane.WARNING_MESSAGE)
            if (loadedCode == null) dispose()
            return
        }
        scope.launch {
            // Nothing translated yet, so the file is metadata and section structure only. That is
            // deliberate: every key falls back to English until someone fills it in here.
            val body = LanguageFileWriter(localizationManager.englishTemplate()).write(
                LanguageFileMeta(code, code, code, emptyList(), isRtl = false),
                emptyMap()
            )
            withContext(Dispatchers.IO) { file.writeText(body) }
            withContext(Dispatchers.Swing) { loadLanguage(code) }
        }
    }

    private fun deleteLanguage() {
        val code = loadedCode ?: return
        val confirm = JOptionPane.showConfirmDialog(
            this, text("delete_confirm", code), title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (confirm != JOptionPane.YES_OPTION) return

        scope.launch {
            withContext(Dispatchers.IO) {
                File(localizationManager.languagesDirectory, "$code.toml").delete()
            }
            withContext(Dispatchers.Swing) {
                localizationManager.forget(LanguageCode(code))
                dirty = false
                dispose()
            }
        }
    }

    // ── Row actions ───────────────────────────────────────────────────────────

    private fun suggestForSelection() {
        val translate = translateString ?: return
        val selected = table.selectedRows.map { table.convertRowIndexToModel(it) }
        if (selected.isEmpty()) return

        val target = LanguageCode(localeField.text.trim().ifBlank { loadedCode.orEmpty() })
        scope.launch {
            for (index in selected) {
                val row = model.rowAt(index)
                val result = translate(row.english, target)
                withContext(Dispatchers.Swing) {
                    // Filled in but not saved, and still editable: a suggestion is a draft to
                    // check, and a translation nobody read is exactly what the guide warns against.
                    result.getOrNull()?.let { model.setTranslation(index, it) }
                }
            }
        }
    }

    private fun resetSelection() {
        table.selectedRows
            .map { table.convertRowIndexToModel(it) }
            .forEach { model.setTranslation(it, "") }
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────

    private fun applyFilter() {
        val needle = searchField.text.trim().lowercase()
        val onlyMissing = untranslatedOnly.isSelected
        sorter.rowFilter = object : RowFilter<StringsModel, Int>() {
            override fun include(entry: Entry<out StringsModel, out Int>): Boolean {
                val row = model.rowAt(entry.identifier)
                if (onlyMissing && row.translation.isNotBlank()) return false
                if (needle.isEmpty()) return true
                return needle in row.key.lowercase() ||
                    needle in row.english.lowercase() ||
                    needle in row.translation.lowercase()
            }
        }
    }

    private fun updateCoverage() {
        val done = model.translations().size
        val total = english.size
        coverageLabel.text = text("coverage", done, total, if (total == 0) 100 else done * 100 / total)
    }

    private fun markDirty() {
        dirty = true
        updateCoverage()
        updateDetailsSummary()
    }

    private fun setEnabledForContent(enabled: Boolean) {
        listOf<JComponent>(nameField, nativeNameField, localeField, translatorsField, rtlCheck, table)
            .forEach { it.isEnabled = enabled }
    }

    private fun closeWithGuard() {
        if (dirty) {
            val choice = JOptionPane.showConfirmDialog(
                this, text("discard_confirm"), title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
            )
            if (choice != JOptionPane.YES_OPTION) return
        }
        dispose()
    }

    private fun text(key: String, vararg args: Any): String =
        localizationManager.getString("language_editor.$key", *args)

    private fun JTextField.onEdit(action: () -> Unit) {
        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = action()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = action()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = action()
        })
    }

    /**
     * Marks progress, and reserves the warning colour for actual problems.
     *
     * This used to paint every untranslated row amber. On a translation nobody has started that is
     * every row, so the colour described the default state and therefore said nothing, while
     * fighting the one cell the reader is trying to work in. A warning that is always on is not a
     * warning.
     *
     * Untranslated is now simply the plain state, and what gets marked is a row that is *wrong*:
     * a translation whose format placeholders do not match the English. That is a genuine defect —
     * `getString` runs the result through `format`, so a dropped `%s` throws when the string is
     * next needed — and it is invisible without help.
     *
     * Colour is never the only signal: a mismatched row also carries a tooltip saying what is
     * wrong, and the done column is a glyph rather than a shade.
     */
    private class UntranslatedAwareRenderer(
        private val model: StringsModel
    ) : DefaultTableCellRenderer() {

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): java.awt.Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val entry = model.rowAt(table.convertRowIndexToModel(row))
            val mismatch = entry.hasPlaceholderMismatch()

            font = font.deriveFont(Font.PLAIN)
            toolTipText = if (mismatch) model.placeholderWarning else null

            // Selection paints its own foreground; overriding it would make the selected row
            // unreadable in exchange for a distinction the highlight has already made.
            if (!isSelected) {
                foreground = when {
                    mismatch -> UIManager.getColor("Component.warning.focusedBorderColor")
                        ?: UIManager.getColor("Label.foreground")
                    // Reference material, so it recedes. The translation is what is being read.
                    column == StringsModel.COL_ENGLISH -> UIManager.getColor("Label.disabledForeground")
                    else -> UIManager.getColor("Table.foreground")
                }
            }
            return this
        }
    }

    /** A tick against the rows that are done, so progress reads at a glance rather than absence. */
    private class DoneRenderer(private val model: StringsModel) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): java.awt.Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val done = model.rowAt(table.convertRowIndexToModel(row)).translation.isNotBlank()
            text = if (done) "✓" else ""
            horizontalAlignment = CENTER
            if (!isSelected) {
                foreground = UIManager.getColor("Actions.Green")
                    ?: UIManager.getColor("Label.disabledForeground")
            }
            return this
        }
    }

    private data class Row(val key: String, val english: String, var translation: String) {

        /**
         * Whether the translation's format placeholders differ from the English.
         *
         * `LocalizationManager.getString` finishes with `raw.format(*args)`, so a translation that
         * drops a `%s`, invents one, or reorders positional ones throws when the string is next
         * needed — from the middle of building whatever screen wanted it. The editor shows these
         * as ordinary text in a narrow cell with nothing marking them as load-bearing, so a
         * translator has no way to know. Comparing counts catches every case that actually throws.
         */
        fun hasPlaceholderMismatch(): Boolean {
            if (translation.isBlank()) return false
            return placeholdersOf(english) != placeholdersOf(translation)
        }

        private fun placeholdersOf(text: String): Map<String, Int> =
            PLACEHOLDER.findAll(text)
                .map { it.value }
                // %% is an escaped literal percent, not an argument, so it cannot go missing.
                .filterNot { it == "%%" }
                .groupingBy { it }
                .eachCount()

        private companion object {
            /** `%s`, `%d`, `%1$s` and the rest of what `String.format` will consume. */
            val PLACEHOLDER = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]""")
        }
    }

    private class StringsModel(
        private val rows: List<Row>,
        /** Column headings are read by the same people the editor exists for, so they translate. */
        private val headings: List<String>,
        /** Shown as a tooltip on a row whose placeholders do not match the English. */
        val placeholderWarning: String
    ) : AbstractTableModel() {

        override fun getRowCount() = rows.size
        override fun getColumnCount() = headings.size
        override fun getColumnName(column: Int) = headings[column]

        override fun getValueAt(row: Int, column: Int): String = when (column) {
            COL_DONE -> ""
            COL_KEY -> rows[row].key
            COL_ENGLISH -> rows[row].english
            else -> rows[row].translation
        }

        override fun isCellEditable(row: Int, column: Int) = column == COL_TRANSLATION

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (column != COL_TRANSLATION) return
            rows[row].translation = (value as? String).orEmpty()
            fireTableCellUpdated(row, column)
        }

        fun rowAt(index: Int) = rows[index]

        fun setTranslation(index: Int, value: String) {
            rows[index].translation = value
            fireTableCellUpdated(index, COL_TRANSLATION)
        }

        fun replaceTranslations(values: Map<String, String>) {
            rows.forEach { it.translation = values[it.key].orEmpty() }
            fireTableDataChanged()
        }

        /** Rows whose placeholders disagree with the English, which will throw at runtime. */
        fun mismatches(): List<Row> = rows.filter { it.hasPlaceholderMismatch() }

        /** Only what has actually been translated: a blank cell means the key is left out. */
        fun translations(): Map<String, String> =
            rows.filter { it.translation.isNotBlank() }.associate { it.key to it.translation }

        companion object {
            const val COL_DONE = 0
            const val COL_KEY = 1
            const val COL_ENGLISH = 2
            const val COL_TRANSLATION = 3
        }
    }
}
