package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.FontUtils
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSet
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconSetInfo
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager.Companion.OS_DEFAULT_THEME_ID
import com.github.ahatem.qtranslate.ui.swing.shared.util.toFont
import java.awt.*
import javax.swing.*
import javax.swing.DefaultListCellRenderer
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DisplayValueRenderer

/**
 * How the application looks: theme, icon set, interface scale and fonts.
 *
 * The interface language and the actions that manage translation files used to sit at the top of
 * this page. They moved to [GeneralPanel] — the language is an application-wide preference, not a
 * visual one, and it was the first thing on a page named after themes and fonts.
 */
class AppearancePanel(
    private val store: SettingsStore,
    private val themeManager: ThemeManager,
    private val localizationManager: LocalizationManager,
) : SettingsPanel() {

    private val groupedItems: List<ThemeItem> = buildGroupedItems()

    private lateinit var themeCombo:        JComboBox<ThemeItem>
    private lateinit var iconSetCombo:      JComboBox<IconSetInfo>
    private lateinit var syncWithOsCheck:   JCheckBox
    private lateinit var titleBarCheck:     JCheckBox
    private lateinit var scaleSpinner:      JSpinner
    private lateinit var uiFontCombo:       JComboBox<String>
    private lateinit var uiFontSize:        JSpinner
    private lateinit var editorFontCombo:   JComboBox<String>
    private lateinit var editorFontSize:    JSpinner
    private lateinit var fallbackFontCombo: JComboBox<FallbackFontOption>
    private lateinit var fontPreview:       JLabel

    private val loadingItem = localizationManager.getString("settings_appearance.loading_fonts")
    private val automaticFallbackLabel = localizationManager.getString("settings_appearance.automatic_fallback")

    init { buildUI() }

    private fun buildUI() {

        // ---- Theme ----
        addSeparator(localizationManager.getString("settings_appearance.theme_group"))

        syncWithOsCheck = addCheckbox(
            text     = localizationManager.getString("settings_appearance.theme_sync_os"),
            selected = false,
            onChange = { synced ->
                themeCombo.isEnabled = !synced
                val selectedThemeId = (themeCombo.selectedItem as? ThemeItem.Entry)?.id
                applyDraft(store) { it.copy(themeId = if (synced) OS_DEFAULT_THEME_ID else selectedThemeId ?: it.themeId) }
            }
        )

        // The picker is the choice that "Sync with OS" overrides, so it hangs under it rather than
        // sitting above it as an equal: when the box is ticked this control is disabled, and a
        // disabled control at the top of a section reads as a broken one.
        addNested {
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
        }

        iconSetCombo = JComboBox(IconSet.available().toTypedArray()).apply {
            renderer = DisplayValueRenderer<IconSetInfo>(text = { it?.displayName.orEmpty() })
            addActionListener {
                if (!isUpdatingFromState) {
                    val chosen = selectedItem as? IconSetInfo ?: return@addActionListener
                    applyDraft(store) { it.copy(iconSetId = chosen.id) }
                }
            }
        }
        addRow(localizationManager.getString("settings_appearance.icon_set"), iconSetCombo)
        addHint(localizationManager.getString("settings_appearance.icon_set_hint"))

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
                    val scale = value as Int
                    applyDraft(store) { it.copy(uiScale = scale) }
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
        fallbackFontCombo = JComboBox(arrayOf(FallbackFontOption(loadingItem, loadingItem))).apply { isEnabled = false }

        // The interface font size is no longer offered: it was a second, compounding zoom beside
        // Interface Scale, and raising one quietly enlarged everything while the other stayed put.
        // The stored size still applies (the spinner is kept, just not shown) so existing
        // configurations render as they did, and choosing a family leaves the size alone.
        addRow(localizationManager.getString("settings_appearance.ui_font"),       createFontRow(uiFontCombo,     null))
        addHint(localizationManager.getString("settings_appearance.ui_font_size_note"))
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
    }


    internal fun uiFontComboForTest():       JComboBox<String> = uiFontCombo
    internal fun editorFontComboForTest():   JComboBox<String> = editorFontCombo
    internal fun fallbackFontComboForTest(): JComboBox<*>      = fallbackFontCombo

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

    private fun loadFontsAsync() {
        object : SwingWorker<Array<String>, Void>() {
            // Every system family plus every bundled family registered for lazy loading (Inter,
            // Rubik, Noto Naskh Arabic), each exactly once, and without loading any of them: see
            // FontUtils.getAvailableFontFamilyNames.
            override fun doInBackground(): Array<String> =
                FontUtils.getAvailableFontFamilyNames().sorted().toTypedArray()

            override fun done() {
                val fonts = get()
                listOf(uiFontCombo, editorFontCombo).forEach { combo ->
                    val prev = combo.selectedItem as? String
                    combo.removeAllItems()
                    fonts.forEach { combo.addItem(it) }
                    if (prev != null && prev != loadingItem) combo.selectedItem = prev
                    combo.isEnabled = true
                }

                val fallbackOptions = listOf(FallbackFontOption(FontConfig.AUTOMATIC, automaticFallbackLabel)) +
                    fonts.map { FallbackFontOption(it, it) }
                fallbackFontCombo.removeAllItems()
                fallbackOptions.forEach { fallbackFontCombo.addItem(it) }
                fallbackFontCombo.isEnabled = true

                uiFontCombo.addActionListener     { if (!isUpdatingFromState) updateUiFont() }
                uiFontSize.addChangeListener      { if (!isUpdatingFromState) updateUiFont() }
                editorFontCombo.addActionListener { if (!isUpdatingFromState) updateEditorFont() }
                editorFontSize.addChangeListener  { if (!isUpdatingFromState) updateEditorFont() }
                fallbackFontCombo.addActionListener {
                    if (!isUpdatingFromState) {
                        val option = fallbackFontCombo.selectedItem as? FallbackFontOption ?: return@addActionListener
                        applyDraft(store) { cfg ->
                            cfg.copy(editorFallbackFontConfig = cfg.editorFallbackFontConfig.copy(name = option.storedName))
                        }
                        updatePreview()
                    }
                }

                val config = store.state.value.workingConfiguration
                withoutTrigger {
                    uiFontCombo.selectedItem       = config.uiFontConfig.name
                    editorFontCombo.selectedItem   = config.editorFontConfig.name
                    fallbackFontCombo.selectedItem =
                        fallbackOptions.find { it.storedName == config.editorFallbackFontConfig.name }
                            ?: fallbackOptions.first()
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
        fontPreview.font = FontConfig(name, size).toFont()
    }

    private fun createFontRow(combo: JComboBox<String>, spinner: JSpinner?) =
        JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(combo, BorderLayout.CENTER)
            if (spinner != null) add(spinner, BorderLayout.LINE_END)
        }


    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            val setId = state.workingConfiguration.iconSetId
            iconSetCombo.selectedItem = IconSet.available().firstOrNull { it.id == setId }
                ?: IconSet.available().first()
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

    /**
     * One entry of the fallback-font picker: [storedName] is what gets saved to the configuration,
     * [displayName] is what the picker shows. The two differ only for "Automatic (Recommended)",
     * stored as [FontConfig.AUTOMATIC]; every physical family displays under its own name.
     */
    private data class FallbackFontOption(val storedName: String, val displayName: String) {
        override fun toString() = displayName
    }
}
