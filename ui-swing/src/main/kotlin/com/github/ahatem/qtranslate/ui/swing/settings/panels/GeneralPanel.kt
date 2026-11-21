package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.icons.FlatOptionPaneWarningIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.TranslationCoverage
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.data.SelectionBehavior
import com.github.ahatem.qtranslate.core.settings.data.SelectionReadSource
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.core.settings.system.WindowsStartupRegistration
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.WrapLayout
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DisplayValueRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Cursor
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextField
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Application-wide settings that belong to no other page: the interface language, startup,
 * updates, the selection button, and history.
 *
 * ### Interface language lives here
 *
 * It used to sit on Appearance, next to themes and fonts. That made the page's first section a
 * language picker with four actions attached to it, ahead of the two controls — theme and icons —
 * that the page is actually named for, and it meant the one setting that decides what language
 * every other label is drawn in was filed under how the window looks. The audit put it under
 * General, which is where a user looks first and where "application-wide, not visual" is the
 * whole story of the page.
 *
 * The language *file* actions come with it: creating, importing, editing and deleting a
 * translation take effect immediately and are not part of Apply/Cancel, so they belong beside the
 * picker that selects the translation rather than on a second page. The audit's alternative —
 * a separate language manager — is still the better long-term home for them, and until then the
 * hint under the picker says plainly that they are immediate.
 *
 * Localization keys for this section keep their `settings_appearance.*` names. Renaming them
 * would have emptied every translated string in this section across every language file, and the
 * keys are an internal namespace, not something a translator reads.
 */
class GeneralPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager,
    /** Used for the language list and the translation-file actions. */
    private val scope: CoroutineScope,
    /**
     * Opens the language editor, and returns once it closes so the picker can pick up whatever
     * changed. Absent in contexts that have no dialog to parent it to, which hides the button
     * rather than offering one that does nothing.
     */
    private val openEditor: ((languageCode: String?) -> Unit)? = null,
    /**
     * Whether "Launch on system startup" can do anything here. The registration is a Windows
     * feature (an `HKCU\...\Run` value); showing the toggle on other platforms promised a
     * preference the application could not keep.
     */
    private val isLaunchOnStartupSupported: Boolean = WindowsStartupRegistration.isSupported(),
) : SettingsPanel() {

    private lateinit var launchCheckbox:  JCheckBox
    private lateinit var updatesCheckbox: JCheckBox
    private lateinit var historyCheckbox: JCheckBox
    private lateinit var clearCheckbox:   JCheckBox
    private lateinit var selectionBehaviorCombo: JComboBox<SelectionBehaviorInfo>
    private lateinit var selectionReadSourceCombo: JComboBox<SelectionReadSourceInfo>

    // ── Interface language ───────────────────────────────────────────────────
    private lateinit var languageCombo: JComboBox<LanguageInfo>
    private lateinit var translatorCredit: JPanel

    /** Held so it can be disabled for English, which has no file to edit. */
    private var editButton: JButton? = null

    private data class SelectionBehaviorInfo(
        val behavior: SelectionBehavior,
        val displayName: String
    )

    private data class SelectionReadSourceInfo(
        val source: SelectionReadSource,
        val displayName: String
    )

    init { buildUI() }

    private fun buildUI() {
        buildLanguageSection()

        // ── Startup & Updates (merged — two closely related checkboxes, not two separate sections)
        addSeparator(localizationManager.getString("settings_general.startup_updates_group"))

        launchCheckbox = addCheckbox(
            text = localizationManager.getString("settings_general.launch_on_startup"),
            selected = false,
            enabled = isLaunchOnStartupSupported,
            onChange = { enabled -> applyDraft(store) { it.copy(launchOnSystemStartup = enabled) } }
        )
        // A platform-specific capability is not a universal one: off Windows this toggle could
        // never take effect, so it is disabled and says why instead of storing a preference the
        // application would silently ignore.
        if (!isLaunchOnStartupSupported) {
            addHint(localizationManager.getString("settings_general.launch_on_startup_windows_only"))
        }
        updatesCheckbox = addCheckbox(
            text = localizationManager.getString("settings_general.auto_check_updates"),
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(autoCheckForUpdates = enabled) } }
        )

        // ── Selection
        addSeparator(localizationManager.getString("settings_general.selection_group"))

        val selectionBehaviors = listOf(
            SelectionBehaviorInfo(SelectionBehavior.OFF, localizationManager.getString("settings_general.selection_behavior_off")),
            SelectionBehaviorInfo(SelectionBehavior.SHOW_ICON, localizationManager.getString("settings_general.selection_behavior_icon")),
            SelectionBehaviorInfo(SelectionBehavior.TRANSLATE, localizationManager.getString("settings_general.selection_behavior_translate")),
            SelectionBehaviorInfo(SelectionBehavior.TRANSLATE_AND_READ, localizationManager.getString("settings_general.selection_behavior_read"))
        )
        selectionBehaviorCombo = JComboBox(selectionBehaviors.toTypedArray()).apply {
            renderer = DisplayValueRenderer<SelectionBehaviorInfo>(text = { it?.displayName.orEmpty() })
            addActionListener {
                if (!isUpdatingFromState) {
                    val behavior = (selectedItem as? SelectionBehaviorInfo)?.behavior ?: return@addActionListener
                    applyDraft(store) { it.copy(selectionBehavior = behavior) }
                }
            }
        }
        addRow(localizationManager.getString("settings_general.selection_behavior"), selectionBehaviorCombo)
        addHint(localizationManager.getString("settings_general.selection_behavior_hint"))

        val selectionReadSources = listOf(
            SelectionReadSourceInfo(
                SelectionReadSource.SOURCE,
                localizationManager.getString("settings_general.selection_read_original")
            ),
            SelectionReadSourceInfo(
                SelectionReadSource.TRANSLATION,
                // Named for what is actually read out. The old label was the page's own name
                // ("Translation"), so the value described a place rather than the content.
                localizationManager.getString("settings_general.selection_read_translated")
            )
        )
        selectionReadSourceCombo = JComboBox(selectionReadSources.toTypedArray()).apply {
            renderer = DisplayValueRenderer<SelectionReadSourceInfo>(text = { it?.displayName.orEmpty() })
            addActionListener {
                if (!isUpdatingFromState) {
                    val source = (selectedItem as? SelectionReadSourceInfo)?.source
                        ?: return@addActionListener
                    applyDraft(store) { it.copy(selectionReadSource = source) }
                }
            }
        }
        // Nested under the behaviour it depends on. Read-aloud only runs for one of the four
        // behaviours above, and as a peer row it read as a fifth, independent choice; the rail
        // says which setting owns it, and the control greys out when that setting is elsewhere.
        addNested {
            addRow(localizationManager.getString("settings_general.selection_read_aloud"), selectionReadSourceCombo)
        }

        // ── History
        addSeparator(localizationManager.getString("settings_general.history_group"))

        historyCheckbox = addCheckbox(
            text = localizationManager.getString("settings_general.enable_history"),
            selected = false,
            onChange = { enabled ->
                applyDraft(store) { it.copy(isHistoryEnabled = enabled) }
            }
        )
        // Only means anything while history is being kept, so it sits under the switch that keeps
        // it rather than beside it.
        addNested {
            clearCheckbox = addCheckbox(
                text = localizationManager.getString("settings_general.clear_history_on_exit"),
                selected = false,
                enabled = false,
                onChange = { enabled -> applyDraft(store) { it.copy(clearHistoryOnExit = enabled) } }
            )
            addHint(localizationManager.getString("settings_general.clear_history_hint"))
        }

        finishLayout()
    }

    // ── Interface language ───────────────────────────────────────────────────

    private fun buildLanguageSection() {
        addSeparator(localizationManager.getString("settings_appearance.language_group"))

        languageCombo = JComboBox<LanguageInfo>().apply {
            isEnabled = false
            renderer  = languageRenderer()
            addActionListener {
                // Follows the selection whether or not the user made it, so the credit always
                // describes the language actually showing rather than the last one chosen by hand.
                updateTranslatorCredit(selectedItem as? LanguageInfo)
                if (!isUpdatingFromState) {
                    val selected = selectedItem as? LanguageInfo ?: return@addActionListener
                    applyDraft(store) { it.copy(interfaceLanguage = selected.code) }
                }
            }
        }
        // One named action and a menu, rather than four bare glyphs in a strip.
        //
        // Four monochrome icons of the same weight side by side is a row where no single icon has
        // to be understood and all four have to be guessed at once, and the guessing gets no easier
        // for the fact that three of them are rare. The common action says what it is in words; the
        // other three are in a menu where they also get words, and no glyph has to carry a meaning
        // on its own.
        val actions = if (openEditor == null) emptyList() else listOf(
            JButton(localizationManager.getString("settings_appearance.edit_button")).apply {
                toolTipText = localizationManager.getString("settings_appearance.edit_tooltip")
                addActionListener {
                    openEditor.invoke((languageCombo.selectedItem as? LanguageInfo)?.code)
                    loadLanguageListAsync()
                }
            }.also { editButton = it },
            pickerAction(
                Icons.MORE,
                localizationManager.getString("settings_appearance.more_actions")
            ) { }.also { more ->
                more.addActionListener { languageMenu().show(more, 0, more.height) }
            }
        )
        addPickerRow(
            localizationManager.getString("settings_appearance.interface_language"),
            languageCombo,
            actions
        )

        // These actions write files, not settings: importing, creating, editing and deleting a
        // translation take effect at once and Cancel cannot undo them. Saying so is the honest
        // minimum until language management has a surface of its own; a Cancel button that looked
        // like a way back was the misleading part.
        addHint(localizationManager.getString("settings_appearance.language_files_immediate"))

        // One line under the picker, and only when there is something to say.
        //
        // There were three, all in small dimmed text. The hint apologised permanently for a bug
        // to every user on every visit and contradicted itself in one sentence. The credit is an
        // acknowledgement rather than a setting, and now rides on the picker's own tooltip. What
        // survives is the only one of the three the reader can act on.
        //
        // WrapLayout, not FlowLayout: FlowLayout reports a single row's height whatever it holds,
        // so the GridBag row was sized for one line and anything that wrapped was clipped away.
        translatorCredit = JPanel(WrapLayout(FlowLayout.LEADING, 0, 3)).apply {
            isOpaque = false
            isVisible = false
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(UIScale.scale(6), UIScale.scale(2), UIScale.scale(2), 0).add(translatorCredit)

        loadLanguageListAsync()
    }

    /**
     * Which entry the language picker should show for a stored [configured] value.
     *
     * Blank means the user has never chosen and the interface is following the operating system,
     * so the picker shows the language actually running rather than nothing at all. Reading the
     * stored value alone left the control empty on a first run, which reads as a bug in a dialog
     * whose whole job is to show current settings.
     */
    private fun selectedLanguageCode(configured: String): String =
        configured.ifBlank { localizationManager.activeLanguage.tag }

    private fun loadLanguageListAsync() {
        scope.launch(Dispatchers.IO) {
            val languages = buildLanguageList()
            withContext(Dispatchers.Swing) {
                withoutTrigger {
                    languageCombo.removeAllItems()
                    languages.forEach { languageCombo.addItem(it) }
                    val currentCode = selectedLanguageCode(
                        store.state.value.workingConfiguration.interfaceLanguage
                    )
                    for (i in 0 until languageCombo.itemCount) {
                        if (languageCombo.getItemAt(i).code == currentCode) {
                            languageCombo.selectedIndex = i
                            break
                        }
                    }
                    languageCombo.isEnabled = true
                }
            }
        }
    }

    private suspend fun buildLanguageList(): List<LanguageInfo> {
        val builtIn = listOf(
            LanguageInfo(
                code = "en",
                displayName = localizationManager.getString("settings_appearance.builtin_english"),
                coverage = localizationManager.coverageOf(LanguageCode.ENGLISH)
            )
        )

        val external = localizationManager.availableLanguages
            .filter { it != "en" }
            .map { code ->
                val meta    = localizationManager.readLanguageMeta(LanguageCode(code))
                val display = if (meta != null) "${meta.name} (${meta.nativeName})" else code
                LanguageInfo(
                    code = code,
                    displayName = display,
                    translators = meta?.translators.orEmpty(),
                    coverage = localizationManager.coverageOf(LanguageCode(code)),
                    // Only a file in the user's own languages folder can be removed. A bundled one is
                    // read from the installation and reappears there after any delete, so offering
                    // to delete it would only appear to succeed. Deleting the user's copy instead
                    // falls back to the bundled translation, which is what the action promises.
                    isRemovable = localizationManager.isUserLanguage(code)
                )
            }
            .sortedBy { it.displayName }

        return builtIn + external
    }

    private fun languageRenderer() = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            text = (value as? LanguageInfo)?.displayName ?: ""
            return this
        }
    }

    /**
     * Names the people behind the chosen translation, each linking to their GitHub profile.
     *
     * Translators had no credit anywhere in the application: the file recorded a name and nothing
     * ever read it. An earlier attempt put the handles in the dropdown beside every language,
     * which was worse than nothing: a bare word like `bovirus` next to a language does not read as
     * a person, and nothing said where it came from or that it led anywhere.
     *
     * So it sits under the picker instead, describing the one language the user has chosen. The
     * sentence says what the names are and which site they are on, each handle is a link with the
     * profile address on hover, and the row disappears entirely when a translation credits nobody
     * rather than leaving a label with nothing after it.
     */
    private fun updateTranslatorCredit(info: LanguageInfo?) {
        // English is the source every other translation is completed from and has no file on
        // disk. Editing it opened a dialog with 546 empty rows, and saving that wrote a phantom
        // file the picker then filtered out of sight.
        editButton?.isEnabled = info != null && info.code != "en"
        editButton?.toolTipText = localizationManager.getString(
            if (info?.code == "en") "settings_appearance.edit_english_tooltip"
            else "settings_appearance.edit_tooltip"
        )

        // Deletable only when there is a file of ours to delete. The bundled translations live in
        // the jar, so offering to remove one promises something that cannot happen.

        translatorCredit.removeAll()
        val handles = info?.translators.orEmpty()
        val coverage = info?.coverage

        // Said out loud, on its own line. It spent a while as the picker's tooltip, which is a
        // place credit goes to be never read: you have to already suspect it is there and hover to
        // find out. Thanking someone where nobody looks is not thanking them.
        languageCombo.toolTipText = null
        if (handles.isNotEmpty()) creditSentence(handles).forEach { translatorCredit.add(it) }

        // No separator dot between the credit and the warning. The two wrap independently, so the
        // dot ended up stranded at the end of one line with the thing it was joining on the next.
        // A line break says the same thing and cannot come apart.

        // Said plainly, and only when it is true. A missing string falls back to English, so an
        // unfinished translation works — it just quietly shows a language the user did not pick,
        // and nothing anywhere admitted it.
        if (coverage != null && !coverage.isComplete && coverage.total > 0) {
            translatorCredit.add(
                incompleteWarning(
                    localizationManager.getString(
                        "settings_appearance.translation_incomplete",
                        coverage.percent,
                        coverage.missing
                    )
                )
            )
        }

        translatorCredit.isVisible = translatorCredit.componentCount > 0
        translatorCredit.revalidate()
        translatorCredit.repaint()
    }

    /**
     * "Translated by @a, @b on GitHub", with each handle a link to that profile.
     *
     * Built by splitting the one format string around its `%s` rather than gluing "Translated by"
     * to a list and "on GitHub" to the end. A translator can then put the words in whatever order
     * their language needs, and the handles land wherever the sentence puts them.
     */
    private fun creditSentence(handles: List<String>): List<JComponent> {
        // Formatted with a sentinel and split on that. The raw template cannot be read back:
        // getString ends in String.format and throws if the %s is left unfilled, and splitting
        // the formatted text on whitespace instead cuts "Translated by" in half.
        val template = localizationManager.getString("settings_appearance.translated_by", NAME_SLOT)
        val before = template.substringBefore(NAME_SLOT, missingDelimiterValue = "")
        val after = template.substringAfter(NAME_SLOT, missingDelimiterValue = "")
        val separator = localizationManager.getString("settings_appearance.name_separator")

        // Named in full up to a point, then counted. Eight handles ran the whole width of the
        // dialog and turned an acknowledgement into a wall; the rest are on the hover.
        val named = handles.take(CREDIT_NAMES)
        val remaining = handles.size - named.size

        val parts = mutableListOf<JComponent>()
        if (before.isNotBlank()) parts += mutedLabel(before.trimEnd(), gapAfter = true)
        named.forEachIndexed { index, handle ->
            parts += profileLink(handle)
            val isLast = index == named.lastIndex
            // The separator hangs off the name before it with no gap in between, so it reads
            // "@a, @b". Laid out as a free-standing label it took the row's gap on both sides and
            // came out as "@a , @b".
            if (!isLast || remaining > 0) {
                parts += mutedLabel(separator.trim().ifEmpty { "," }, gapAfter = true)
            }
        }
        if (remaining > 0) {
            parts += mutedLabel(
                localizationManager.getString("settings_appearance.credit_more", remaining),
                gapAfter = true
            ).apply { toolTipText = handles.joinToString(", ") { "@$it" } }
        }
        if (after.isNotBlank()) parts += mutedLabel(after.trimStart(), gapBefore = true)
        return parts
    }

    private fun mutedLabel(text: String, gapBefore: Boolean = false, gapAfter: Boolean = false) =
        JLabel(text).apply {
            foreground = SettingsVisuals.secondaryText()
            font = font.deriveFont(font.size - 1f)
            val gap = UIScale.scale(4)
            border = BorderFactory.createEmptyBorder(0, if (gapBefore) gap else 0, 0, if (gapAfter) gap else 0)
        }

    /** One handle, as a link to the GitHub profile it names. */
    private fun profileLink(handle: String) = JLabel("@$handle").apply {
        val url = "https://github.com/$handle"
        foreground = UIManager.getColor("Component.linkColor")
            ?: UIManager.getColor("Component.accentColor")
            ?: UIManager.getColor("Label.foreground")
        font = font.deriveFont(font.size - 1f)
        // The address itself, so it is clear where this goes before it is clicked.
        toolTipText = url
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = openUrl(url)
        })
    }

    private fun openUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI(url))
            }
        }
    }

    /**
     * Sits beside the credit rather than in the dropdown.
     *
     * In the list it would be one more thing on every row, and the only moment it matters is when
     * the user has settled on a language: this is the point at which "some of this will still be
     * English" is worth knowing.
     */
    private fun incompleteWarning(text: String) = JLabel(text).apply {
        icon = ScaledIcon(FlatOptionPaneWarningIcon(), UIScale.scale(13))
        iconTextGap = 5
        // The row carries no gap of its own so the credit's commas sit tight against their names,
        // which leaves this to space itself off whatever precedes it.
        border = BorderFactory.createEmptyBorder(0, UIScale.scale(14), 0, 0)
        foreground = UIManager.getColor("Component.warning.focusedBorderColor")
            ?: UIManager.getColor("Label.foreground")
        font = font.deriveFont(font.size - 1f)
    }

    /**
     * Draws an icon at a size it was not built for.
     *
     * FlatLaf's warning icon is the one the option pane uses, so it comes at that size and offers
     * no way to ask for another. It is drawn rather than bitmapped, so scaling it costs nothing in
     * quality, and borrowing the look and feel's own glyph keeps this consistent with every other
     * warning in the application and correct in whatever theme is loaded.
     */
    private class ScaledIcon(private val delegate: Icon, private val size: Int) : Icon {
        override fun getIconWidth() = size
        override fun getIconHeight() = size

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.translate(x, y)
                // Against the delegate's own reported width, which the look and feel has already
                // scaled for the display, so this does not scale a second time on top of that.
                val factor = size.toDouble() / delegate.iconWidth
                g2.scale(factor, factor)
                delegate.paintIcon(c, g2, 0, 0)
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * The three rarer language actions, named.
     *
     * Rebuilt each time it opens so Delete reflects whatever is selected now, rather than whatever
     * was selected when the row was first laid out.
     */
    private fun languageMenu(): JPopupMenu = JPopupMenu().apply {
        add(JMenuItem(localizationManager.getString("settings_appearance.menu_new")).apply {
            addActionListener {
                openEditor?.invoke(null)
                loadLanguageListAsync()
            }
        })
        add(JMenuItem(localizationManager.getString("settings_appearance.menu_import")).apply {
            addActionListener { importLanguage() }
        })
        addSeparator()
        add(JMenuItem(localizationManager.getString("settings_appearance.menu_delete")).apply {
            val info = languageCombo.selectedItem as? LanguageInfo
            // Only a file of ours can be removed; the bundled translations are read out of the jar
            // and would still be there afterwards.
            isEnabled = info?.isRemovable == true
            foreground = UIManager.getColor("Component.error.focusedBorderColor") ?: foreground
            addActionListener { deleteSelectedLanguage() }
        })
    }

    // ── Installing and removing translations ──────────────────────────────────

    /**
     * Copies a translation file into the languages folder.
     *
     * Parsed before it is copied, and refused if it will not parse. The alternative is a file that
     * lands successfully and then fails at load, leaving someone to work out why the language they
     * just installed is not in the list.
     */
    private fun importLanguage() {
        val chooser = JFileChooser().apply {
            dialogTitle = localizationManager.getString("settings_appearance.import_title")
            fileFilter = FileNameExtensionFilter(
                localizationManager.getString("settings_appearance.import_filter"), "toml"
            )
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val source = chooser.selectedFile ?: return

        scope.launch {
            val meta = withContext(Dispatchers.IO) {
                runCatching { LanguageTomlParser().parse(source.readText()).meta }.getOrNull()
            }
            // The locale inside the file wins over the filename: the name is whatever it picked up
            // being emailed around, the locale is what the translation says it is.
            val code = meta?.locale?.trim()?.takeIf { it.isNotEmpty() } ?: source.nameWithoutExtension
            if (meta == null || !LOCALE.matches(code)) {
                withContext(Dispatchers.Swing) { importFailed("settings_appearance.import_invalid") }
                return@launch
            }

            val target = File(localizationManager.languagesDirectory, "$code.toml")
            if (withContext(Dispatchers.IO) { target.exists() }) {
                val replace = JOptionPane.showConfirmDialog(
                    this@GeneralPanel,
                    localizationManager.getString("settings_appearance.import_exists", code),
                    localizationManager.getString("settings_appearance.import_title"),
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
                )
                if (replace != JOptionPane.YES_OPTION) return@launch
            }

            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                }.isSuccess
            }
            withContext(Dispatchers.Swing) {
                if (!copied) { importFailed("settings_appearance.import_failed"); return@withContext }
                localizationManager.forget(LanguageCode(code))
                loadLanguageListAsync()
            }
        }
    }

    private fun importFailed(messageKey: String) {
        JOptionPane.showMessageDialog(
            this,
            localizationManager.getString(messageKey),
            localizationManager.getString("settings_appearance.import_title"),
            JOptionPane.ERROR_MESSAGE
        )
    }

    private fun deleteSelectedLanguage() {
        val info = languageCombo.selectedItem as? LanguageInfo ?: return
        if (!info.isRemovable) return

        val confirm = JOptionPane.showConfirmDialog(
            this,
            localizationManager.getString("settings_appearance.delete_confirm", info.displayName),
            localizationManager.getString("settings_appearance.delete_title"),
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (confirm != JOptionPane.YES_OPTION) return

        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { File(localizationManager.languagesDirectory, "${info.code}.toml").delete() }
            }
            withContext(Dispatchers.Swing) {
                localizationManager.forget(LanguageCode(info.code))
                // The file is gone, so the interface cannot stay pointed at it. The fallback is
                // persisted rather than staged: it describes what is now on disk, and leaving it in
                // an unsaved draft would keep a deleted translation selected until someone pressed
                // Apply — or lose the correction entirely if they pressed Cancel.
                if (localizationManager.activeLanguage.tag == info.code ||
                    selectedLanguageCode(store.state.value.originalConfiguration.interfaceLanguage) == info.code
                ) {
                    store.dispatch(SettingsIntent.ToggleSetting { it.copy(interfaceLanguage = "en") })
                }
                loadLanguageListAsync()
            }
        }
    }

    private data class LanguageInfo(
        val code: String,
        val displayName: String,
        /** GitHub handles of everyone who worked on this translation. Empty for the built-in. */
        val translators: List<String> = emptyList(),
        val coverage: TranslationCoverage = TranslationCoverage(0, 0),
        /** Whether a file of ours backs this, and so whether deleting it can do anything. */
        val isRemovable: Boolean = false
    ) {
        override fun toString() = displayName
    }

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration

        withoutTrigger {
            for (i in 0 until languageCombo.itemCount) {
                if (languageCombo.getItemAt(i).code == selectedLanguageCode(c.interfaceLanguage)) {
                    languageCombo.selectedIndex = i
                    break
                }
            }

            launchCheckbox.isSelected  = c.launchOnSystemStartup
            updatesCheckbox.isSelected = c.autoCheckForUpdates
            for (index in 0 until selectionBehaviorCombo.itemCount) {
                if (selectionBehaviorCombo.getItemAt(index).behavior == c.selectionBehavior) {
                    selectionBehaviorCombo.selectedIndex = index
                    break
                }
            }
            for (index in 0 until selectionReadSourceCombo.itemCount) {
                if (selectionReadSourceCombo.getItemAt(index).source == c.selectionReadSource) {
                    selectionReadSourceCombo.selectedIndex = index
                    break
                }
            }
            selectionReadSourceCombo.isEnabled = c.selectionBehavior == SelectionBehavior.TRANSLATE_AND_READ
            historyCheckbox.isSelected = c.isHistoryEnabled
            clearCheckbox.isSelected   = c.clearHistoryOnExit
            clearCheckbox.isEnabled    = c.isHistoryEnabled
        }
    }

    private companion object {
        /** A BCP 47 tag, loosely: enough to keep an imported filename out of trouble. */
        val LOCALE = Regex("""[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*""")

        /**
         * Stands in for the name list while the credit sentence is taken apart. A control
         * character, so it cannot collide with anything a translator would write.
         */
        const val NAME_SLOT = "\u0001"

        /** How many handles the credit names before it starts counting the rest. */
        const val CREDIT_NAMES = 3
    }
}
