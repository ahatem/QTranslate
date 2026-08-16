package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.icons.FlatOptionPaneWarningIcon
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.TranslationCoverage
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager.Companion.OS_DEFAULT_THEME_ID
import com.github.ahatem.qtranslate.ui.swing.shared.util.WrapLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.*
import javax.swing.*
import javax.swing.DefaultListCellRenderer

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
        val actions = if (openEditor == null) emptyList() else listOf(
            pickerAction(
                "icons/lucide/pen-line.svg",
                localizationManager.getString("settings_appearance.edit_tooltip")
            ) {
                openEditor.invoke((languageCombo.selectedItem as? LanguageInfo)?.code)
                loadLanguageListAsync()
            }.also { editButton = it },
            pickerAction(
                "icons/lucide/plus.svg",
                localizationManager.getString("settings_appearance.new_tooltip")
            ) {
                openEditor.invoke(null)
                loadLanguageListAsync()
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
        translatorCredit = JPanel(WrapLayout(FlowLayout.LEADING, 4, 2)).apply {
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
                    coverage = localizationManager.coverageOf(LanguageCode(code))
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
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            when (val item = value) {
                is ThemeItem.Header -> {
                    super.getListCellRendererComponent(list, value, index, false, false)
                    text = item.label
                    font = font.deriveFont(Font.BOLD, font.size - 1f)
                    foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                    border = BorderFactory.createEmptyBorder(if (index <= 1) 4 else 10, 6, 2, 4)
                    background = list?.background ?: background
                    isOpaque = true
                }
                is ThemeItem.Entry -> {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
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

        translatorCredit.removeAll()
        val handles = info?.translators.orEmpty()
        val coverage = info?.coverage

        // The acknowledgement rides on the control rather than taking a line of its own. One
        // format string, not a sentence assembled from fragments, so a translator can order the
        // words as their language requires instead of being handed "Translated by" and "on
        // GitHub" as fixed bookends.
        languageCombo.toolTipText = if (handles.isEmpty()) null else {
            localizationManager.getString(
                "settings_appearance.translated_by",
                handles.joinToString(localizationManager.getString("settings_appearance.name_separator"))
            )
        }

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
     * Sits beside the credit rather than in the dropdown.
     *
     * In the list it would be one more thing on every row, and the only moment it matters is when
     * the user has settled on a language: this is the point at which "some of this will still be
     * English" is worth knowing.
     */
    private fun incompleteWarning(text: String) = JLabel(text).apply {
        icon = ScaledIcon(FlatOptionPaneWarningIcon(), UIScale.scale(13))
        iconTextGap = 5
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
        val coverage: TranslationCoverage = TranslationCoverage(0, 0)
    ) {
        override fun toString() = displayName
    }

}