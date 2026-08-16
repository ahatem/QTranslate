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
    private val model = StringsModel(rows)
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
        setLocationRelativeTo(owner)
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildHeader(): JComponent {
        val details = JPanel(GridBagLayout()).apply {
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

        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 0, 12)
            add(details, BorderLayout.CENTER)
            add(filters, BorderLayout.SOUTH)
        }
    }

    private fun buildTable(): JComponent {
        table.apply {
            rowSorter = sorter
            rowHeight = UIScale.scale(26)
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            fillsViewportHeight = true
            putClientProperty(FlatClientProperties.STYLE, "showHorizontalLines: true")
            columnModel.getColumn(0).preferredWidth = UIScale.scale(220)
            columnModel.getColumn(1).preferredWidth = UIScale.scale(300)
            columnModel.getColumn(2).preferredWidth = UIScale.scale(300)
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
            val parsed = withContext(Dispatchers.IO) {
                runCatching { parser.parse(file.readText()) }.getOrNull()
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
            }
        }
    }

    private fun save() {
        val code = loadedCode ?: return
        val meta = LanguageFileMeta(
            name = nameField.text.trim(),
            nativeName = nativeNameField.text.trim().ifBlank { nameField.text.trim() },
            locale = localeField.text.trim().ifBlank { code },
            translators = translatorsField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() },
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
     * Marks the rows nobody has translated yet.
     *
     * The filter can hide everything else, but scrolling with the filter off is how someone reads
     * a translation in context, and untranslated strings were indistinguishable from translated
     * ones while doing it. Colouring them means the gaps are findable without changing what is on
     * screen, and the untranslated cell reads "English" rather than sitting empty and ambiguous.
     */
    private class UntranslatedAwareRenderer(
        private val model: StringsModel
    ) : DefaultTableCellRenderer() {

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): java.awt.Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val untranslated = model.rowAt(table.convertRowIndexToModel(row)).translation.isBlank()

            font = if (untranslated && column == StringsModel.COL_KEY) {
                font.deriveFont(Font.BOLD)
            } else {
                font.deriveFont(Font.PLAIN)
            }

            // Selection paints its own foreground; overriding it would make the selected row
            // unreadable in exchange for a distinction the highlight has already made.
            if (!isSelected) {
                foreground = when {
                    untranslated -> table.warningColour()
                    column == StringsModel.COL_ENGLISH -> UIManager.getColor("Label.disabledForeground")
                    else -> UIManager.getColor("Table.foreground")
                }
            }
            return this
        }

        private fun JTable.warningColour() =
            UIManager.getColor("Component.warning.focusedBorderColor")
                ?: UIManager.getColor("Actions.Yellow")
                ?: foreground
    }

    private data class Row(val key: String, val english: String, var translation: String)

    private class StringsModel(private val rows: List<Row>) : AbstractTableModel() {

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = when (column) {
            COL_KEY -> "Key"
            COL_ENGLISH -> "English"
            else -> "Translation"
        }

        override fun getValueAt(row: Int, column: Int): String = when (column) {
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

        /** Only what has actually been translated: a blank cell means the key is left out. */
        fun translations(): Map<String, String> =
            rows.filter { it.translation.isNotBlank() }.associate { it.key to it.translation }

        companion object {
            const val COL_KEY = 0
            const val COL_ENGLISH = 1
            const val COL_TRANSLATION = 2
        }
    }
}
