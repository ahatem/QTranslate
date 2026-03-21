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
import java.awt.*
import javax.swing.*

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
    private lateinit var scaleSlider:       JSlider
    private lateinit var scaleLabel:        JLabel
    private lateinit var uiFontCombo:       JComboBox<String>
    private lateinit var uiFontSize:        JSpinner
    private lateinit var editorFontCombo:   JComboBox<String>
    private lateinit var editorFontSize:    JSpinner
    private lateinit var fallbackFontCombo: JComboBox<String>
    private lateinit var fontPreview:       JLabel

    private val loadingItem = "<Loading fonts…>"

    init { buildUI() }

    private fun buildUI() {

        // ---- Language ----
        addSeparator("Language")

        languageCombo = JComboBox(buildLanguageList().toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val selected = selectedItem as? LanguageInfo ?: return@addActionListener
                    // Persist the selection
                    applyDraft(store) { it.copy(interfaceLanguage = selected.code) }
                    // Apply immediately — no restart needed for most strings
                    scope.launch(Dispatchers.IO) {
                        localizationManager.loadLanguage(LanguageCode(selected.code))
                    }
                }
            }
        }
        addRow("Interface Language:", languageCombo)
        addHint("Changes apply immediately. Some labels may require a restart to update fully.")

        // ---- Theme ----
        addSeparator("Theme")

        themeCombo = JComboBox(themes.toTypedArray()).apply {
            addActionListener {
                if (!isUpdatingFromState) {
                    val theme = selectedItem as? ThemeInfo ?: return@addActionListener
                    applyDraft(store) { it.copy(themeId = theme.id) }
                }
            }
        }
        addRow("Theme:", themeCombo)

        titleBarCheck = addCheckbox(
            text = "Use unified title bar",
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(useUnifiedTitleBar = enabled) } }
        )

        // ---- UI Scale ----
        addSeparator("UI Scale")

        scaleLabel = JLabel("150%").apply {
            preferredSize = Dimension(48, preferredSize.height)
            horizontalAlignment = SwingConstants.RIGHT
        }
        scaleSlider = JSlider(100, 300, 150).apply {
            majorTickSpacing = 50
            minorTickSpacing = 25
            paintTicks = true
            addChangeListener {
                scaleLabel.text = "${value}%"
                if (!valueIsAdjusting && !isUpdatingFromState) {
                    applyDraft(store) { it.copy(uiScale = value) }
                }
            }
        }
        addRow("Scale:", JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(scaleSlider, BorderLayout.CENTER)
            add(scaleLabel,  BorderLayout.EAST)
        })
        addHint("Restart the application to fully apply scale changes.")

        // ---- Fonts ----
        addSeparator("Fonts")

        uiFontCombo       = JComboBox(arrayOf(loadingItem)).apply { isEnabled = false }
        uiFontSize        = JSpinner(SpinnerNumberModel(13, 8, 32, 1))
        editorFontCombo   = JComboBox(arrayOf(loadingItem)).apply { isEnabled = false }
        editorFontSize    = JSpinner(SpinnerNumberModel(15, 8, 32, 1))
        fallbackFontCombo = JComboBox(arrayOf(loadingItem)).apply { isEnabled = false }

        addRow("UI Font:",       createFontRow(uiFontCombo, uiFontSize))
        addRow("Editor Font:",   createFontRow(editorFontCombo, editorFontSize))
        addRow("Fallback Font:", fallbackFontCombo)
        addHint("Fallback font is used for characters the editor font cannot display.")

        fontPreview = JLabel("The quick brown fox jumps over the lazy dog  — 大きな茶色の狐が怠け者の犬を飛び越えた").apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: Color.GRAY),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
            )
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(10, 0, 0, 0).add(fontPreview)

        finishLayout()
        loadFontsAsync()
    }

    // -------------------------------------------------------------------------
    // Language list
    // -------------------------------------------------------------------------

    /**
     * Builds the list of selectable interface languages.
     *
     * English is always first as the built-in fallback — it works even if no
     * `en.toml` file exists in the languages directory.
     * All other languages come from `.toml` files found in `languages/` by
     * [LocalizationManager.availableLanguages], sorted alphabetically.
     * Display names use the `name` and `native_name` fields from the file's
     * `[meta]` section when available, falling back to the raw language code.
     */
    private fun buildLanguageList(): List<LanguageInfo> {
        val builtIn = listOf(LanguageInfo("en", "English (built-in)"))

        val external = localizationManager.availableLanguages
            .filter { it != "en" }
            .map { code ->
                val meta = localizationManager.getLanguageMeta(LanguageCode(code))
                val display = if (meta != null) "${meta.name} (${meta.nativeName})" else code
                LanguageInfo(code, display)
            }
            .sortedBy { it.displayName }

        return builtIn + external
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
            // Match by code — not by object reference
            for (i in 0 until languageCombo.itemCount) {
                if (languageCombo.getItemAt(i).code == c.interfaceLanguage) {
                    languageCombo.selectedIndex = i
                    break
                }
            }

            themeCombo.selectedItem  = themes.find { it.id == c.themeId }
            titleBarCheck.isSelected = c.useUnifiedTitleBar
            scaleSlider.value        = c.uiScale
            scaleLabel.text          = "${c.uiScale}%"
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

    /**
     * @property code  The BCP-47 language code used as the filename stem (e.g. `"ar"`, `"fr"`)
     *                 and stored in [Configuration.interfaceLanguage].
     * @property displayName  Human-readable label shown in the dropdown
     *                        (e.g. `"Arabic (العربية)"` or `"English (built-in)"`).
     */
    private data class LanguageInfo(val code: String, val displayName: String) {
        override fun toString() = displayName
    }
}