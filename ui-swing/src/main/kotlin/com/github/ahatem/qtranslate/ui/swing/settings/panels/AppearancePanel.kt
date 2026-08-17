package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.icons.FlatOptionPaneWarningIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.TranslationCoverage
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSetInfo
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager.Companion.OS_DEFAULT_THEME_ID
import com.github.ahatem.qtranslate.ui.swing.shared.util.WrapLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.DefaultListCellRenderer
import javax.swing.filechooser.FileNameExtensionFilter
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

class AppearancePanel(
    private val store: SettingsStore,
    private val themeManager: ThemeManager,
    private val localizationManager: LocalizationManager,
    private val scope: CoroutineScope,
    /**
     * Opens the language editor, and returns once it closes so the picker can pick up whatever
     * changed. Absent in contexts that have no dialog to parent it to, which hides the button
     * rather than offering one that does nothing.
     */
    private val openEditor: ((languageCode: String?) -> Unit)? = null
) : SettingsPanel() {

    private val groupedItems: List<ThemeItem> = buildGroupedItems()

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

    private lateinit var languageCombo:     JComboBox<LanguageInfo>
    private lateinit var translatorCredit:  JPanel
    /** Held so it can be disabled for English, which has no file to edit. */
    private var editButton: JButton? = null
    private lateinit var themeCombo:        JComboBox<ThemeItem>
    private lateinit var iconSetCombo:      JComboBox<IconSetInfo>
    private lateinit var syncWithOsCheck:   JCheckBox
    private lateinit var titleBarCheck:     JCheckBox
    private lateinit var scaleSpinner:      JSpinner
    private lateinit var uiFontCombo:       JComboBox<String>
    private lateinit var uiFontSize:        JSpinner
    private lateinit var editorFontCombo:   JComboBox<String>
    private lateinit var editorFontSize:    JSpinner
    private lateinit var fallbackFontCombo: JComboBox<String>
    private lateinit var fontPreview:       JLabel

    private val loadingItem = localizationManager.getString("settings_appearance.loading_fonts")

    init { buildUI() }

    private fun buildUI() {

        // ---- Language ----
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
            .insets(6, 2, 2, 0).add(translatorCredit)

        // ---- Theme ----
        addSeparator(localizationManager.getString("settings_appearance.theme_group"))

        themeCombo = JComboBox(buildGroupedModel()).apply {
            renderer = groupedThemeRenderer()
            addActionListener {
                if (!isUpdatingFromState) {
                    val entry = selectedItem as? ThemeItem.Entry ?: return@addActionListener
                    applyDraft(store) { it.copy(themeId = entry.id) }
                }
            }
        }

        addRow(localizationManager.getString("settings_appearance.theme_label"), themeCombo)

        iconSetCombo = JComboBox(IconSet.available().toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val chosen = selectedItem as? IconSetInfo ?: return@addActionListener
                    applyDraft(store) { it.copy(iconSetId = chosen.id) }
                }
            }
        }
        addRow(localizationManager.getString("settings_appearance.icon_set"), iconSetCombo)
        addHint(localizationManager.getString("settings_appearance.icon_set_hint"))

        syncWithOsCheck = addCheckbox(
            text     = localizationManager.getString("settings_appearance.theme_sync_os"),
            selected = false,
            onChange = { synced ->
                themeCombo.isEnabled = !synced
                applyDraft(store) { it.copy(themeId = if (synced) OS_DEFAULT_THEME_ID else (themeCombo.selectedItem as? ThemeItem.Entry)?.id ?: it.themeId) }
            }
        )

        titleBarCheck = addCheckbox(
            text     = localizationManager.getString("settings_appearance.unified_title_bar"),
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(useUnifiedTitleBar = enabled) } }
        )

        // ---- UI Scale ----
        addSeparator(localizationManager.getString("settings_appearance.scale_group"))

        scaleSpinner = JSpinner(SpinnerNumberModel(100, 75, 300, 25)).apply {
            (editor as? JSpinner.NumberEditor)?.textField?.columns = 4
            addChangeListener {
                if (!isUpdatingFromState) {
                    applyDraft(store) { it.copy(uiScale = value as Int) }
                }
            }
        }
        addRow(
            localizationManager.getString("settings_appearance.scale_label"),
            JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
                isOpaque = false
                add(scaleSpinner)
                add(JLabel("  %").apply {
                    foreground = UIManager.getColor("Label.disabledForeground")
                })
            }
        )
        addHint(localizationManager.getString("settings_appearance.scale_hint"))

        // ---- Fonts ----
        addSeparator(localizationManager.getString("settings_appearance.fonts_group"))

        uiFontCombo       = JComboBox(arrayOf(loadingItem)).apply { isEnabled = false }
        uiFontSize        = JSpinner(SpinnerNumberModel(13, 8, 32, 1))
        editorFontCombo   = JComboBox(arrayOf(loadingItem)).apply { isEnabled = false }
        editorFontSize    = JSpinner(SpinnerNumberModel(15, 8, 32, 1))
        fallbackFontCombo = JComboBox(arrayOf(loadingItem)).apply { isEnabled = false }

        addRow(localizationManager.getString("settings_appearance.ui_font"),       createFontRow(uiFontCombo,     uiFontSize))
        addRow(localizationManager.getString("settings_appearance.editor_font"),   createFontRow(editorFontCombo, editorFontSize))
        addRow(localizationManager.getString("settings_appearance.fallback_font"), fallbackFontCombo)
        addHint(localizationManager.getString("settings_appearance.fallback_hint"))

        fontPreview = JLabel(localizationManager.getString("settings_appearance.font_preview_text")).apply {
            // themeAwareBorder() reads the color at paint time — adapts to theme changes.
            border = BorderFactory.createCompoundBorder(
                themeAwareBorder(),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
            )
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(10, 0, 0, 0).add(fontPreview)

        finishLayout()
        loadFontsAsync()
        loadLanguageListAsync()
    }


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
                    // Only a file in the user's own languages folder can be removed. The bundled
                    // ones are read out of the jar and will still be there after any delete.
                    isRemovable = File(localizationManager.languagesDirectory, "$code.toml").exists()
                )
            }
            .sortedBy { it.displayName }

        return builtIn + external
    }


    // ── Grouped theme helpers ─────────────────────────────────────────────────

    private fun buildGroupedItems(): List<ThemeItem> {
        val all = themeManager.getAvailableThemes()
        val light    = all.filter { !it.isDark && !it.id.startsWith("external:") }
            .map { ThemeItem.Entry(it.id, it.name, it.isDark) }
        val dark     = all.filter { it.isDark  && !it.id.startsWith("external:") }
            .map { ThemeItem.Entry(it.id, it.name, it.isDark) }
        val external = all.filter { it.id.startsWith("external:") }
            .map { ThemeItem.Entry(it.id, it.name, it.isDark) }

        return buildList {
            add(ThemeItem.Header(localizationManager.getString("settings_appearance.theme_group_light")))
            addAll(light)
            add(ThemeItem.Header(localizationManager.getString("settings_appearance.theme_group_dark")))
            addAll(dark)
            if (external.isNotEmpty()) {
                add(ThemeItem.Header(localizationManager.getString("settings_appearance.theme_group_installed")))
                addAll(external)
            }
        }
    }

    private fun buildGroupedModel() = object : DefaultComboBoxModel<ThemeItem>(groupedItems.toTypedArray()) {
        override fun setSelectedItem(anObject: Any?) {
            if (anObject is ThemeItem.Entry) super.setSelectedItem(anObject)
        }
    }

    private fun groupedThemeRenderer() = object : DefaultListCellRenderer() {
        // Derived once. This component is reused for every row, so deriving from its own font
        // shrank the headers a little more on each repaint and dragged the entries down with them.
        private val baseFont: Font =
            (UIManager.getFont("List.font") ?: UIManager.getFont("Label.font")).deriveFont(Font.PLAIN)
        private val headerFont: Font = baseFont.deriveFont(Font.BOLD, baseFont.size - 1f)

        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            when (val item = value) {
                is ThemeItem.Header -> {
                    super.getListCellRendererComponent(list, value, index, false, false)
                    text = item.label
                    font = headerFont
                    foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                    border = BorderFactory.createEmptyBorder(if (index <= 1) 4 else 10, 6, 2, 4)
                    background = list?.background ?: background
                    isOpaque = true
                }
                is ThemeItem.Entry -> {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    font = baseFont
                    text = item.displayName
                    // index < 0 = closed button cell — no extra padding so the combo height stays normal
                    //
                    // The indent marks a theme as belonging to the header above it, so it goes on
                    // the side the list reads from. EmptyBorder takes absolute sides, so a fixed
                    // left inset put it on the trailing edge in a right-to-left interface, where
                    // it read as ragged spacing rather than as grouping.
                    if (index >= 0) {
                        border = if (list?.componentOrientation?.isLeftToRight != false) {
                            BorderFactory.createEmptyBorder(2, 12, 2, 4)
                        } else {
                            BorderFactory.createEmptyBorder(2, 4, 2, 12)
                        }
                    }
                }
                else -> super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            }
            return this
        }
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
            foreground = UIManager.getColor("Label.disabledForeground")
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
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = openUrl(url)
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
            val g2 = g.create() as Graphics2D
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

    private fun loadFontsAsync() {
        object : SwingWorker<Array<String>, Void>() {
            override fun doInBackground(): Array<String> =
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .availableFontFamilyNames.sorted().toTypedArray()

            override fun done() {
                val fonts = get()
                listOf(uiFontCombo, editorFontCombo, fallbackFontCombo).forEach { combo ->
                    val prev = combo.selectedItem as? String
                    combo.removeAllItems()
                    fonts.forEach { combo.addItem(it) }
                    if (prev != null && prev != loadingItem) combo.selectedItem = prev
                    combo.isEnabled = true
                }

                uiFontCombo.addActionListener     { if (!isUpdatingFromState) updateUiFont() }
                uiFontSize.addChangeListener      { if (!isUpdatingFromState) updateUiFont() }
                editorFontCombo.addActionListener { if (!isUpdatingFromState) updateEditorFont() }
                editorFontSize.addChangeListener  { if (!isUpdatingFromState) updateEditorFont() }
                fallbackFontCombo.addActionListener {
                    if (!isUpdatingFromState) {
                        val name = fallbackFontCombo.selectedItem as? String ?: return@addActionListener
                        applyDraft(store) { cfg ->
                            cfg.copy(editorFallbackFontConfig = cfg.editorFallbackFontConfig.copy(name = name))
                        }
                        updatePreview()
                    }
                }

                val config = store.state.value.workingConfiguration
                withoutTrigger {
                    uiFontCombo.selectedItem       = config.uiFontConfig.name
                    editorFontCombo.selectedItem   = config.editorFontConfig.name
                    fallbackFontCombo.selectedItem = config.editorFallbackFontConfig.name
                    updatePreview()
                }
            }
        }.execute()
    }

    private fun updateUiFont() {
        val name = uiFontCombo.selectedItem as? String ?: return
        val size = uiFontSize.value as? Int ?: return
        applyDraft(store) { it.copy(uiFontConfig = FontConfig(name, size)) }
        updatePreview()
    }

    private fun updateEditorFont() {
        val name = editorFontCombo.selectedItem as? String ?: return
        val size = editorFontSize.value as? Int ?: return
        applyDraft(store) { it.copy(editorFontConfig = FontConfig(name, size)) }
        updatePreview()
    }

    private fun updatePreview() {
        val name = editorFontCombo.selectedItem as? String ?: return
        val size = editorFontSize.value as? Int ?: return
        fontPreview.font = Font(name, Font.PLAIN, size)
    }

    private fun createFontRow(combo: JComboBox<String>, spinner: JSpinner) =
        JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(combo,   BorderLayout.CENTER)
            add(spinner, BorderLayout.LINE_END)
        }


    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            val setId = state.workingConfiguration.iconSetId
            iconSetCombo.selectedItem = IconSet.available().firstOrNull { it.id == setId }
                ?: IconSet.available().first()
            for (i in 0 until languageCombo.itemCount) {
                if (languageCombo.getItemAt(i).code == selectedLanguageCode(c.interfaceLanguage)) {
                    languageCombo.selectedIndex = i
                    break
                }
            }
            val isSynced = c.themeId == OS_DEFAULT_THEME_ID
            syncWithOsCheck.isSelected = isSynced
            themeCombo.isEnabled = !isSynced
            if (!isSynced) {
                themeCombo.selectedItem = groupedItems.filterIsInstance<ThemeItem.Entry>().find { it.id == c.themeId }
            }
            titleBarCheck.isSelected = c.useUnifiedTitleBar
            scaleSpinner.value       = c.uiScale
            uiFontSize.value         = c.uiFontConfig.size
            editorFontSize.value     = c.editorFontConfig.size
            updatePreview()
        }
    }


    private sealed class ThemeItem {
        data class Header(val label: String) : ThemeItem()
        data class Entry(val id: String, val displayName: String, val isDark: Boolean) : ThemeItem() {
            override fun toString() = displayName
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
                    this@AppearancePanel,
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
                // Falls back to the built-in rather than leaving the interface pointed at a
                // translation that is no longer on disk.
                if (selectedLanguageCode(store.state.value.workingConfiguration.interfaceLanguage) == info.code) {
                    applyDraft(store) { it.copy(interfaceLanguage = "en") }
                }
                loadLanguageListAsync()
            }
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
