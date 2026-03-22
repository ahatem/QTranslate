package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
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
    private val scope: CoroutineScope
) : SettingsPanel() {

    private val themes = themeManager.getAvailableThemes().map {
        ThemeInfo(it.id, it.name, it.isDark)
    }

    private lateinit var languageCombo:     JComboBox<LanguageInfo>
    private lateinit var themeCombo:        JComboBox<ThemeInfo>
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
                if (!isUpdatingFromState) {
                    val selected = selectedItem as? LanguageInfo ?: return@addActionListener
                    // Only update the draft — language is applied on Save, not live.
                    // This avoids triggering orientation changes and app flickering
                    // while the user is still browsing the settings dialog.
                    applyDraft(store) { it.copy(interfaceLanguage = selected.code) }
                }
            }
        }
        addRow(localizationManager.getString("settings_appearance.interface_language"), languageCombo)
        addHint(localizationManager.getString("settings_appearance.language_hint"))

        // ---- Theme ----
        addSeparator(localizationManager.getString("settings_appearance.theme_group"))

        themeCombo = JComboBox(themes.toTypedArray()).apply {
            renderer = themeRenderer()
            addActionListener {
                if (!isUpdatingFromState) {
                    val theme = selectedItem as? ThemeInfo ?: return@addActionListener
                    // Only update the draft — theme is applied on Save, not live.
                    applyDraft(store) { it.copy(themeId = theme.id) }
                }
            }
        }
        addRow(localizationManager.getString("settings_appearance.theme_label"), themeCombo)

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
            JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
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
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: Color.GRAY),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
            )
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(10, 0, 0, 0).add(fontPreview)

        finishLayout()
        loadFontsAsync()
        loadLanguageListAsync()
    }

    // -------------------------------------------------------------------------
    // Language list — loaded off EDT using readLanguageMeta (no side effects)
    // -------------------------------------------------------------------------

    private fun loadLanguageListAsync() {
        scope.launch(Dispatchers.IO) {
            val languages = buildLanguageList()
            withContext(Dispatchers.Swing) {
                withoutTrigger {
                    languageCombo.removeAllItems()
                    languages.forEach { languageCombo.addItem(it) }
                    val currentCode = store.state.value.workingConfiguration.interfaceLanguage
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

    /**
     * Builds the language list using [LocalizationManager.readLanguageMeta] —
     * which reads TOML meta without changing the active language or emitting
     * to [LocalizationManager.activeLanguageFlow]. No orientation changes,
     * no window flicker.
     */
    private suspend fun buildLanguageList(): List<LanguageInfo> {
        val builtIn = listOf(LanguageInfo("en", "English (built-in)"))

        val external = localizationManager.availableLanguages
            .filter { it != "en" }
            .map { code ->
                val meta    = localizationManager.readLanguageMeta(LanguageCode(code))
                val display = if (meta != null) "${meta.name} (${meta.nativeName})" else code
                LanguageInfo(code, display)
            }
            .sortedBy { it.displayName }

        return builtIn + external
    }

    // -------------------------------------------------------------------------
    // Renderers
    // -------------------------------------------------------------------------

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

    private fun themeRenderer() = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            text = (value as? ThemeInfo)?.displayName ?: ""
            return this
        }
    }

    // -------------------------------------------------------------------------
    // Font loading
    // -------------------------------------------------------------------------

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
            add(spinner, BorderLayout.EAST)
        }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            for (i in 0 until languageCombo.itemCount) {
                if (languageCombo.getItemAt(i).code == c.interfaceLanguage) {
                    languageCombo.selectedIndex = i
                    break
                }
            }
            themeCombo.selectedItem  = themes.find { it.id == c.themeId }
            titleBarCheck.isSelected = c.useUnifiedTitleBar
            scaleSpinner.value       = c.uiScale
            uiFontSize.value         = c.uiFontConfig.size
            editorFontSize.value     = c.editorFontConfig.size
            updatePreview()
        }
    }

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

    private data class ThemeInfo(val id: String, val displayName: String, val isDark: Boolean) {
        override fun toString() = displayName
    }

    private data class LanguageInfo(val code: String, val displayName: String) {
        override fun toString() = displayName
    }
}