package com.github.ahatem.qtranslate.ui.swing.settings

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGIcon
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
    private val rtlCheck = JCheckBox(text("rtl"))

    /**
     * Who has worked on this translation, in the order they arrived.
     *
     * Held as a list and edited by adding and removing, never as one editable string. A comma
     * separated field invites replacing what is in it, and the one rule this list has is that you
     * append to it: a translation is rarely one person's work for long and everyone who has
     * touched it stays credited.
     */
    private val translators = mutableListOf<String>()

    /** Names the first few and counts the rest. Never more than one line, whatever it holds. */
    private val translatorsSummary = JLabel()

    /**
     * One line, always, with the managing done somewhere that has room.
     *
     * Chips in the form itself were the wrong shape twice over: unbounded they pushed the strings
     * table off the window, and bounded they became a scrollbar inside a form field, which is a
     * scrollbar in the last place anyone wants to find one. A form row should be a fixed height
     * and say what the value is; editing a list of things belongs in a window that can be as tall
     * as the list. This is how a repository host shows assignees or an issue tracker shows labels.
     */
    private val translatorsField = JPanel(BorderLayout(UIScale.scale(6), 0)).apply {
        isOpaque = false
        add(translatorsSummary, BorderLayout.CENTER)
    }

    private val detailsPanel = JPanel(GridBagLayout()).apply { isOpaque = false }

    /** Row actions, held so they can be greyed out while nothing is selected. */
    private var suggestButton: JButton? = null
    private var resetButton: JButton? = null

    /** Says "Saved" in the footer for a moment, in place of a dialog to dismiss. */
    private val saveNotice = JLabel()

    private var loadedCode: String? = null
    private var baseTitle: String = ""
    private var dirty = false

    init {
        baseTitle = text("title")
        refreshTitle()
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

    /**
     * The block describing the language itself, above the strings.
     *
     * Always open. It used to collapse behind a borderless "Details" button in the top-right, to
     * buy four more rows a screen — but nobody found the button, so what it actually bought was a
     * window whose language had no name on it. Four rows are cheaper than that.
     */
    private fun buildHeader(): JComponent {
        val details = detailsPanel.apply {
            val c = GridBagConstraints().apply {
                insets = Insets(3, 0, 3, 8); anchor = GridBagConstraints.LINE_START
                fill = GridBagConstraints.HORIZONTAL
            }
            fun field(label: String, comp: JComponent, x: Int, y: Int, weight: Double) {
                c.gridx = x; c.gridy = y; c.weightx = 0.0; c.gridwidth = 1
                add(JLabel(text(label)), c)
                c.gridx = x + 1; c.weightx = weight
                add(comp, c)
            }
            field("name", nameField, 0, 0, 0.5)
            field("native_name", nativeNameField, 2, 0, 0.5)
            field("locale", localeField, 0, 1, 0.5)
            field("translators", translatorsField, 2, 1, 0.5)

            // Deleting the language is not an editing action and has no business in the window you
            // do the editing in — you would have to open a translation to throw it away. It lives
            // in the language picker in Settings, beside the rest of the actions that add and
            // remove translations.
            c.gridx = 0; c.gridy = 2; c.gridwidth = 4; c.weightx = 1.0
            add(rtlCheck, c)
        }
        translatorsField.add(JButton(text("translators_manage")).apply {
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
            addActionListener { manageTranslators() }
        }, BorderLayout.LINE_END)
        listOf(nameField, nativeNameField, localeField).forEach { it.onEdit { markDirty() } }
        rtlCheck.addActionListener { markDirty() }

        val filters = JPanel(BorderLayout(UIScale.scale(8), 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(10, 0, 8, 0)
            searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, text("search"))
            searchField.onEdit { applyFilter() }
            untranslatedOnly.addActionListener { applyFilter() }
            // The two actions that operate on the selected rows, kept with the rows. In the footer
            // they sat beside Close and Save and read as if they acted on the whole dialog.
            add(buildRowActions(), BorderLayout.LINE_START)
            add(searchField, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, UIScale.scale(8), 0)).apply {
                isOpaque = false
                add(untranslatedOnly)
                add(coverageLabel.apply { font = font.deriveFont(Font.BOLD) })
            }, BorderLayout.LINE_END)
        }

        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 0, 12)
            add(details.withRuleBelow(), BorderLayout.CENTER)
            add(filters, BorderLayout.SOUTH)
        }
    }

    /** Wraps [this] with the standard rule underneath, dividing it from what follows. */
    private fun JComponent.withRuleBelow(): JComponent =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            add(this@withRuleBelow, BorderLayout.CENTER)
            add(JPanel().apply {
                isOpaque = false
                border = BorderFactory.createMatteBorder(
                    1, 0, 0, 0, UIManager.getColor("Component.borderColor") ?: java.awt.Color.GRAY
                )
                preferredSize = Dimension(0, 1)
            }, BorderLayout.SOUTH)
        }

    /**
     * Both act on the selected rows, so both are off until there are some.
     *
     * Left always enabled they were two buttons that could be pressed at any time and did nothing
     * most of them, which reads as the feature being broken rather than as nothing being selected.
     */
    private fun buildRowActions(): JComponent =
        JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(6), 0)).apply {
            isOpaque = false
            if (translateString != null) {
                suggestButton = JButton(text("suggest")).apply {
                    toolTipText = text("suggest_tooltip")
                    isEnabled = false
                    addActionListener { suggestForSelection() }
                }
                add(suggestButton)
            }
            resetButton = JButton(text("reset_row")).apply {
                toolTipText = text("reset_row_tooltip")
                isEnabled = false
                addActionListener { resetSelection() }
            }
            add(resetButton)

            table.selectionModel.addListSelectionListener {
                val any = table.selectedRowCount > 0
                suggestButton?.isEnabled = any
                resetButton?.isEnabled = any
            }
        }

    // ── Translator credits ────────────────────────────────────────────────────

    /** Restates the summary line from [translators]. */
    private fun rebuildTranslators() {
        val muted = UIManager.getColor("Label.disabledForeground")
        if (translators.isEmpty()) {
            translatorsSummary.text = text("translators_empty")
            translatorsSummary.foreground = muted
            translatorsSummary.toolTipText = null
            return
        }
        val shown = translators.take(SUMMARY_NAMES).joinToString(", ") { "@$it" }
        val rest = translators.size - SUMMARY_NAMES
        translatorsSummary.text = if (rest > 0) "$shown  ${text("translators_more", rest)}" else shown
        translatorsSummary.foreground = UIManager.getColor("Label.foreground")
        // Everyone, on hover, so the count is never the only way to find out who is behind it.
        translatorsSummary.toolTipText = translators.joinToString(", ") { "@$it" }
    }

    /**
     * The list, in a window with room for it.
     *
     * Cancel leaves the credits exactly as they were, so this is somewhere you can look at the
     * list without committing to having changed it.
     */
    private fun manageTranslators() {
        val listModel = DefaultListModel<String>().apply { translators.forEach { addElement(it) } }
        val list = JList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 8
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    l: JList<*>, value: Any?, index: Int, sel: Boolean, focus: Boolean
                ): java.awt.Component =
                    super.getListCellRendererComponent(l, "@$value", index, sel, focus)
            }
        }

        val removeButton = JButton(text("translators_remove")).apply {
            isEnabled = false
            addActionListener {
                val index = list.selectedIndex
                if (index >= 0) {
                    listModel.remove(index)
                    list.selectedIndex = index.coerceAtMost(listModel.size() - 1)
                }
            }
        }
        list.addListSelectionListener { removeButton.isEnabled = list.selectedIndex >= 0 }

        val addButton = JButton(text("add_translator")).apply {
            addActionListener {
                val handle = JOptionPane.showInputDialog(
                    this@LanguageEditorDialog, text("add_translator_prompt"),
                    text("translators_title"), JOptionPane.PLAIN_MESSAGE
                )?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() } ?: return@addActionListener

                // Appended, and only once. The order records who arrived when, so someone already
                // on the list keeps their place instead of jumping to the end.
                val already = (0 until listModel.size()).any { listModel[it].equals(handle, ignoreCase = true) }
                if (!already) listModel.addElement(handle)
            }
        }

        val panel = JPanel(BorderLayout(UIScale.scale(8), UIScale.scale(8))).apply {
            preferredSize = Dimension(UIScale.scale(320), UIScale.scale(240))
            add(JScrollPane(list).apply {
                border = BorderFactory.createLineBorder(
                    UIManager.getColor("Component.borderColor") ?: java.awt.Color.GRAY
                )
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, UIScale.scale(6), 0)).apply {
                isOpaque = false
                add(addButton)
                add(removeButton)
            }, BorderLayout.SOUTH)
        }

        val result = JOptionPane.showConfirmDialog(
            this, panel, text("translators_title"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result != JOptionPane.OK_OPTION) return

        val updated = (0 until listModel.size()).map { listModel[it] }
        if (updated != translators) {
            translators.clear()
            translators += updated
            markDirty()
            rebuildTranslators()
        }
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
                cellRenderer = DoneRenderer(
                    this@LanguageEditorDialog.model,
                    isFilteredToUntranslated = { untranslatedOnly.isSelected }
                )
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
                StatusRenderer(
                    this@LanguageEditorDialog.model,
                    text("untranslated_row"),
                    // Whether the untranslated rows are the only ones on screen. See
                    // [StatusRenderer] for why that decides the colour.
                    isFilteredToUntranslated = { untranslatedOnly.isSelected }
                )
            )
        }
        model.addTableModelListener { if (it.column == StringsModel.COL_TRANSLATION) markDirty() }

        val line = UIManager.getColor("Component.borderColor") ?: java.awt.Color.GRAY
        val scroll = JScrollPane(table).apply {
            // The table is the content of this window and it was drawn floating on the dialog
            // background with nothing to say where it began. Every other panel in the application
            // that holds a list draws this same line.
            border = BorderFactory.createLineBorder(line)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            // Bottom margin so the table's own line and the footer's rule do not meet as a
            // double stripe.
            border = BorderFactory.createEmptyBorder(0, 12, 12, 12)
            add(scroll, BorderLayout.CENTER)
        }
    }

    /**
     * Only the two actions that finish with the dialog.
     *
     * It used to hold five buttons of three different scopes in a row: two that acted on the
     * selected table rows, one that destroyed the file, and two that closed the window. Sorting
     * them by what they act on put the row actions with the rows and the delete with the rest of
     * the language's own settings, and left this bar saying one thing.
     */
    private fun buildFooter(): JComponent {
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
            // The same rule the status bar and the document dialog's action bar draw: a button bar
            // is a separate region from the content above it and says so with a line.
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                    1, 0, 0, 0, UIManager.getColor("Component.borderColor") ?: java.awt.Color.GRAY
                ),
                BorderFactory.createEmptyBorder(10, 12, 12, 12)
            )
            add(saveNotice.apply { foreground = UIManager.getColor("Label.disabledForeground") }, BorderLayout.LINE_START)
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
                baseTitle = text("title_for", meta?.name ?: code, code)
                refreshTitle()
                nameField.text = meta?.name.orEmpty()
                nativeNameField.text = meta?.nativeName.orEmpty()
                localeField.text = meta?.locale ?: code
                translators.clear()
                translators += meta?.translators.orEmpty()
                rebuildTranslators()
                rtlCheck.isSelected = meta?.isRtl == true
                model.replaceTranslations(parsed?.entries.orEmpty())
                setEnabledForContent(true)
                dirty = false
                updateCoverage()
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
            translators = translators.toList(),
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
                refreshTitle()
                // Reported in place rather than through a dialog. Save is the action taken most
                // often in this window, and a modal to dismiss every few minutes is a toll on the
                // one thing the user does constantly.
                saveNotice.text = text("saved")
                Timer(2500) { saveNotice.text = "" }.apply { isRepeats = false }.start()
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
        // The untranslated-only checkbox decides a colour as well as a filter, so the rows that
        // survive it have to be redrawn, not just re-selected. See [StatusRenderer].
        table.repaint()
    }

    private fun updateCoverage() {
        val done = model.translations().size
        val total = english.size
        coverageLabel.text = text("coverage", done, total, if (total == 0) 100 else done * 100 / total)
    }

    private fun markDirty() {
        val wasClean = !dirty
        dirty = true
        if (wasClean) refreshTitle()
        updateCoverage()
    }

    /**
     * Marks unsaved work in the title bar with the same dot the settings window uses.
     *
     * Nothing else said the editor held changes. Someone could translate for twenty minutes and
     * close it with no more warning than a confirmation they had no reason to expect.
     */
    private fun refreshTitle() {
        title = if (dirty) "● $baseTitle" else baseTitle
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
     * Colours a row by what is wrong with it, in two grades.
     *
     * **Red — a translation that will throw.** Its format placeholders do not match the English.
     * `getString` runs the result through `format`, so a dropped `%s` fails when the string is next
     * needed, from the middle of building whatever screen wanted it, and nothing about the cell
     * says the text is load-bearing.
     *
     * **Amber — not translated yet.** Only while the untranslated-only filter is *off*. Amber here
     * means "this one, among these", and with the filter on every visible row is untranslated, so
     * the colour would describe the whole screen and therefore say nothing while fighting the cell
     * being worked in. Turning the filter on is already the stronger statement of the same thing,
     * which is why the colour steps out of the way when it does.
     *
     * Colour is never the only signal: both states carry a tooltip saying what they mean, and the
     * done column is a glyph rather than a shade.
     */
    private class StatusRenderer(
        private val model: StringsModel,
        /** Shown as a tooltip on a row nobody has translated. */
        private val untranslatedNote: String,
        private val isFilteredToUntranslated: () -> Boolean
    ) : DefaultTableCellRenderer() {

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): java.awt.Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val entry = model.rowAt(table.convertRowIndexToModel(row))
            val mismatch = entry.hasPlaceholderMismatch()
            val untranslated = entry.translation.isBlank() && !isFilteredToUntranslated()

            font = font.deriveFont(Font.PLAIN)
            // A warning if there is one, and otherwise the cell's own text. Keys run past the
            // column and get an ellipsis, and a key you cannot read is a key you cannot look up in
            // the English file — which is where the comments explaining its placeholders live.
            val full = (value as? String).orEmpty()
            toolTipText = when {
                mismatch -> model.placeholderWarning
                untranslated -> untranslatedNote
                full.isNotBlank() -> full
                else -> null
            }

            // Selection paints its own foreground; overriding it would make the selected row
            // unreadable in exchange for a distinction the highlight has already made.
            if (!isSelected) {
                foreground = when {
                    mismatch -> UIManager.getColor("Component.error.focusedBorderColor")
                        ?: UIManager.getColor("Label.foreground")
                    untranslated -> UIManager.getColor("Component.warning.focusedBorderColor")
                        ?: UIManager.getColor("Label.foreground")
                    // Reference material, so it recedes. The translation is what is being read.
                    column == StringsModel.COL_ENGLISH -> UIManager.getColor("Label.disabledForeground")
                    else -> UIManager.getColor("Table.foreground")
                }
            }
            return this
        }
    }

    /**
     * The state of the row as a mark, so it survives being the only signal.
     *
     * A tick for done, and a warning triangle for a row that needs attention — the same two states
     * [StatusRenderer] colours, on the same terms, so the icon and the colour never disagree.
     * Someone who cannot separate the amber from the surrounding text still sees the triangle.
     */
    private class DoneRenderer(
        private val model: StringsModel,
        private val isFilteredToUntranslated: () -> Boolean
    ) : DefaultTableCellRenderer() {

        private val doneIcon = themedIcon("icons/lucide/check.svg", "Actions.Green")
        private val warnIcon = themedIcon("icons/lucide/triangle-alert.svg", "Component.warning.focusedBorderColor")
        private val errorIcon = themedIcon("icons/lucide/triangle-alert.svg", "Component.error.focusedBorderColor")

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): java.awt.Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val entry = model.rowAt(table.convertRowIndexToModel(row))
            text = ""
            horizontalAlignment = CENTER
            icon = when {
                entry.hasPlaceholderMismatch() -> errorIcon
                entry.translation.isNotBlank() -> doneIcon
                // With the filter on every row here is untranslated, so a column of identical
                // triangles would only repeat what the checkbox already said.
                isFilteredToUntranslated() -> null
                else -> warnIcon
            }
            return this
        }

        private companion object {
            /** A 14px lucide glyph repainted in [colorKey], resolved from the theme at paint time. */
            fun themedIcon(path: String, colorKey: String): Icon? = runCatching {
                FlatSVGIcon(path, 14, 14, DoneRenderer::class.java.classLoader).apply {
                    colorFilter = FlatSVGIcon.ColorFilter {
                        UIManager.getColor(colorKey) ?: UIManager.getColor("Label.foreground")
                    }
                } as Icon
            }.getOrNull()
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

        /**
         * Every cell here holds a string, and saying so is what makes the row colouring work.
         *
         * `AbstractTableModel` answers `Object` unless told otherwise, and `JTable` picks a
         * renderer by asking the model for the column's class. So a renderer registered for
         * `String` was never once consulted: the status icons appeared, because that renderer is
         * attached to its column directly, while the key and English cells stayed the default
         * colour and the amber never showed.
         */
        override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

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

    private companion object {
        /** How many handles the one-line summary names before it starts counting the rest. */
        const val SUMMARY_NAMES = 2
    }
}
