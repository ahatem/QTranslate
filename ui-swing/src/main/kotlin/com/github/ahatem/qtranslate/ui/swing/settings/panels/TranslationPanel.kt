package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.api.plugin.StandardOptions
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.ServiceOptionChoice
import com.github.ahatem.qtranslate.ui.swing.shared.util.choices
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.DisplayValueRenderer
import java.awt.*
import javax.swing.*

/**
 * Settings panel for translation behaviour and extra output configuration.
 *
 * ### Sections
 * 1. **Behaviour** — instant translation toggle, spell-check toggle, line-break
 *    removal.
 * 2. **Extra Output** — selects the second output type (backward translation,
 *    summarise, or rewrite), with conditional sub-settings for summary length
 *    and rewrite style.
 * 3. **Dictionary lookup** — what happens after a translation, and which side of it
 *    gets looked up.
 *
 * Language configuration itself (defaults, pinned languages, translation rules)
 * lives in [LanguagesPanel].
 */
class TranslationPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager
) : SettingsPanel() {

    private lateinit var dictAutoLookupCheck: JCheckBox
    private lateinit var dictAutoUseTranslated: JRadioButton
    private lateinit var dictAutoUseSource: JRadioButton
    private lateinit var dictAutoPopupCheck: JCheckBox

    /**
     * The standard vocabularies, not the active service's.
     *
     * This dialog sets a preference that outlives any particular service, and it has no live
     * service to ask — the picker in the extra-output pane is the one that offers what the
     * service actually declares, including anything a plugin invented. Both write the same id, so
     * a choice made here still applies when a service offering it is selected.
     */
    private val summaryLengths by lazy { StandardOptions.SUMMARY_LENGTH.choices(localizationManager) }

    private val rewriteStyles by lazy { StandardOptions.REWRITE_STYLE.choices(localizationManager) }

    private val types by lazy {
        listOf(
            ExtraOutputTypeInfo(ExtraOutputType.None,             localizationManager.getString("settings_translation.type_none")),
            ExtraOutputTypeInfo(ExtraOutputType.BackwardTranslate, localizationManager.getString("settings_translation.type_backward")),
            ExtraOutputTypeInfo(ExtraOutputType.Summarize,        localizationManager.getString("settings_translation.type_summarize")),
            ExtraOutputTypeInfo(ExtraOutputType.Rewrite,          localizationManager.getString("settings_translation.type_rewrite"))
        )
    }

    private lateinit var instantCheck:        JCheckBox
    private lateinit var spellCheck:          JCheckBox
    private lateinit var removeLineBreaksCheck: JCheckBox
    private lateinit var typeCombo:           JComboBox<ExtraOutputTypeInfo>
    private lateinit var useTranslated:       JRadioButton
    private lateinit var useInput:            JRadioButton

    // Conditional setting rows — shown only for the relevant extra output type
    private lateinit var summaryLengthRow:    JPanel
    private lateinit var summaryLengthCombo:  JComboBox<ServiceOptionChoice>
    private lateinit var rewriteStyleRow:     JPanel
    private lateinit var rewriteStyleCombo:   JComboBox<ServiceOptionChoice>

    init { buildUI() }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private fun buildUI() {

        // ---- Behaviour ----
        addSeparator(localizationManager.getString("settings_translation.behavior_group"))

        instantCheck = addCheckbox(
            text     = localizationManager.getString("settings_translation.instant_translation"),
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(isInstantTranslationEnabled = enabled) } }
        )
        addHint(localizationManager.getString("settings_translation.instant_hint"))

        spellCheck = addCheckbox(
            text     = localizationManager.getString("settings_translation.spell_check_input"),
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(isSpellCheckingEnabled = enabled) } }
        )

        removeLineBreaksCheck = addCheckbox(
            text     = localizationManager.getString("settings_translation.remove_line_breaks"),
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(isRemoveLineBreaksEnabled = enabled) } }
        )
        addHint(localizationManager.getString("settings_translation.remove_line_breaks_hint"))

        // ---- Extra Output ----
        addSeparator(localizationManager.getString("settings_translation.extra_output_group"))

        typeCombo = JComboBox<ExtraOutputTypeInfo>(types.toTypedArray()).apply {
            renderer = DisplayValueRenderer<ExtraOutputTypeInfo>(text = { it?.displayName.orEmpty() })
            addActionListener {
                if (!isUpdatingFromState) {
                    val type = (selectedItem as? ExtraOutputTypeInfo)?.type ?: return@addActionListener
                    applyDraft(store) { it.copy(extraOutputType = type) }
                }
            }
        }
        addRow(localizationManager.getString("settings_translation.extra_output_type"), typeCombo)
        addHint(localizationManager.getString("settings_translation.extra_output_hint"))

        // Summary length — only visible when type = Summarize
        summaryLengthCombo = JComboBox<ServiceOptionChoice>(summaryLengths.toTypedArray()).apply {
            renderer = DisplayValueRenderer<ServiceOptionChoice>(text = { it?.label.orEmpty() })
            addActionListener {
                if (!isUpdatingFromState) {
                    val length = (selectedItem as? ServiceOptionChoice)?.id ?: return@addActionListener
                    applyDraft(store) { it.copy(summaryLength = length) }
                }
            }
        }
        summaryLengthRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque  = false
            isVisible = false
            add(JLabel(localizationManager.getString("settings_translation.summary_length")), BorderLayout.LINE_START)
            add(summaryLengthCombo, BorderLayout.CENTER)
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0).add(summaryLengthRow)

        // Rewrite style — only visible when type = Rewrite
        rewriteStyleCombo = JComboBox<ServiceOptionChoice>(rewriteStyles.toTypedArray()).apply {
            renderer = DisplayValueRenderer<ServiceOptionChoice>(text = { it?.label.orEmpty() })
            addActionListener {
                if (!isUpdatingFromState) {
                    val style = (selectedItem as? ServiceOptionChoice)?.id ?: return@addActionListener
                    applyDraft(store) { it.copy(rewriteStyle = style) }
                }
            }
        }
        rewriteStyleRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque  = false
            isVisible = false
            add(JLabel(localizationManager.getString("settings_translation.rewrite_style")), BorderLayout.LINE_START)
            add(rewriteStyleCombo, BorderLayout.CENTER)
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0).add(rewriteStyleRow)

        // Extra output source radio buttons
        useTranslated = JRadioButton(localizationManager.getString("settings_translation.source_use_translated")).apply {
            addActionListener {
                if (isSelected && !isUpdatingFromState)
                    applyDraft(store) { it.copy(extraOutputSource = ExtraOutputSource.Output) }
            }
        }
        useInput = JRadioButton(localizationManager.getString("settings_translation.source_use_input")).apply {
            addActionListener {
                if (isSelected && !isUpdatingFromState)
                    applyDraft(store) { it.copy(extraOutputSource = ExtraOutputSource.Input) }
            }
        }
        ButtonGroup().apply { add(useTranslated); add(useInput) }

        val radioPanel = JPanel().apply {
            layout   = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(useTranslated)
            add(Box.createVerticalStrut(4))
            add(useInput)
        }
        gb.nextRow().add(JLabel(localizationManager.getString("settings_translation.extra_output_source")))
        gb.weightX(1.0).fill(GridBagConstraints.HORIZONTAL).anchor(GridBagConstraints.LINE_START).add(radioPanel)

        // ── Dictionary lookup ────────────────────────────────────────────────
        // Moved here from Languages. It decides what happens after a translation, which is
        // behaviour; its localization keys were already `settings_translation.*`, so it had
        // drifted away from where it was written to belong.
        //
        // The on/off switch and the source choice used to be one three-way dropdown (Off /
        // Translated / Source), which hid "off" as a value alongside two real choices rather than
        // as its own state. A checkbox plus a radio group that only matters while it's checked says
        // the same thing more directly. This still writes the same three-value
        // Configuration.dictionaryAutoSource field -- no new field, no migration -- the checkbox
        // reads/writes `!= OFF`, and turning it back on restores whichever radio was last shown
        // rather than resetting to a fixed choice, the same as reselecting a non-Off dropdown entry
        // used to.
        addSeparator(localizationManager.getString("settings_languages.dict_auto_lookup_group"))
        addHint(localizationManager.getString("settings_languages.dict_auto_lookup_hint"))

        dictAutoLookupCheck = addCheckbox(
            text = localizationManager.getString("settings_translation.dict_auto_lookup_enabled"),
            selected = true,
            onChange = { enabled ->
                applyDraft(store) {
                    it.copy(
                        dictionaryAutoSource = if (enabled) {
                            if (dictAutoUseSource.isSelected) DictionaryAutoSource.SOURCE else DictionaryAutoSource.TRANSLATED
                        } else {
                            DictionaryAutoSource.OFF
                        }
                    )
                }
            }
        )

        dictAutoUseTranslated = JRadioButton(localizationManager.getString("settings_translation.dict_auto_source_translated")).apply {
            addActionListener {
                if (isSelected && !isUpdatingFromState)
                    applyDraft(store) { it.copy(dictionaryAutoSource = DictionaryAutoSource.TRANSLATED) }
            }
        }
        dictAutoUseSource = JRadioButton(localizationManager.getString("settings_translation.dict_auto_source_source")).apply {
            addActionListener {
                if (isSelected && !isUpdatingFromState)
                    applyDraft(store) { it.copy(dictionaryAutoSource = DictionaryAutoSource.SOURCE) }
            }
        }
        ButtonGroup().apply { add(dictAutoUseTranslated); add(dictAutoUseSource) }

        val dictAutoSourceRow = JPanel().apply {
            layout   = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(dictAutoUseTranslated)
            add(Box.createVerticalStrut(4))
            add(dictAutoUseSource)
        }
        addRow(localizationManager.getString("settings_translation.dict_auto_lookup_source_label"), dictAutoSourceRow)

        // Visually subordinate to the checkbox above it -- it only matters when auto-lookup is on,
        // and indenting says so without another line of copy.
        dictAutoPopupCheck = JCheckBox(localizationManager.getString("settings_languages.dict_auto_popup_enabled")).apply {
            addActionListener { if (!isUpdatingFromState) applyDraft(store) { it.copy(isDictionaryAutoPopupEnabled = isSelected) } }
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(0, UIScale.scale(20), 0, 0).add(dictAutoPopupCheck)
        registerSearchEntry(localizationManager.getString("settings_languages.dict_auto_popup_enabled"), dictAutoPopupCheck)

        finishLayout()
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            instantCheck.isSelected        = c.isInstantTranslationEnabled
            spellCheck.isSelected          = c.isSpellCheckingEnabled
            removeLineBreaksCheck.isSelected = c.isRemoveLineBreaksEnabled
            typeCombo.selectedItem         = types.find { it.type == c.extraOutputType }

            when (c.extraOutputSource) {
                ExtraOutputSource.Output -> useTranslated.isSelected = true
                ExtraOutputSource.Input  -> useInput.isSelected      = true
            }
            val extraEnabled = c.extraOutputType != ExtraOutputType.None
            useTranslated.isEnabled = extraEnabled
            useInput.isEnabled      = extraEnabled

            summaryLengthCombo.selectedItem = summaryLengths.find { it.id == c.summaryLength }
            rewriteStyleCombo.selectedItem  = rewriteStyles.find { it.id == c.rewriteStyle }
            summaryLengthRow.isVisible      = c.extraOutputType == ExtraOutputType.Summarize
            rewriteStyleRow.isVisible       = c.extraOutputType == ExtraOutputType.Rewrite

            val autoLookupEnabled = c.dictionaryAutoSource != DictionaryAutoSource.OFF
            dictAutoLookupCheck.isSelected = autoLookupEnabled
            // Only move the radios when there's a real choice to show -- OFF carries no source of
            // its own, so leave whichever one was last selected in place (dimmed) rather than
            // forcing a choice back onto the user the moment they uncheck the box above.
            when (c.dictionaryAutoSource) {
                DictionaryAutoSource.TRANSLATED -> dictAutoUseTranslated.isSelected = true
                DictionaryAutoSource.SOURCE     -> dictAutoUseSource.isSelected     = true
                DictionaryAutoSource.OFF        -> Unit
            }
            dictAutoUseTranslated.isEnabled = autoLookupEnabled
            dictAutoUseSource.isEnabled     = autoLookupEnabled
            dictAutoPopupCheck.isSelected   = c.isDictionaryAutoPopupEnabled
            dictAutoPopupCheck.isEnabled    = autoLookupEnabled
        }
    }

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

    private data class ExtraOutputTypeInfo(val type: ExtraOutputType, val displayName: String)
}

// ---------------------------------------------------------------------------
// LanguageCode.all() — shared extension used by LanguagesPanel and others
// ---------------------------------------------------------------------------

fun LanguageCode.Companion.all(): List<String> = listOf(
    ENGLISH, CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL, HINDI, SPANISH, FRENCH,
    ARABIC, BENGALI, RUSSIAN, PORTUGUESE, INDONESIAN, URDU, GERMAN, JAPANESE,
    SWAHILI, MARATHI, TELUGU, TURKISH, TAMIL, VIETNAMESE, KOREAN, ITALIAN,
    THAI, GUJARATI, JAVANESE, FARSI, HAUSA, BURMESE, POLISH, UKRAINIAN,
    YORUBA, DUTCH, GREEK, HEBREW, HUNGARIAN, CZECH, SWEDISH, ROMANIAN,
    DANISH, FINNISH, BULGARIAN, NORWEGIAN, SLOVAK, SLOVENIAN, CATALAN,
    SERBIAN, CROATIAN, MALAY, NEPALI, SINHALA, KHMER, LAO, AMHARIC,
    SOMALI, ZULU, AFRIKAANS, ALBANIAN, ARMENIAN, AZERBAIJANI, BASQUE,
    BELARUSIAN, BOSNIAN, ESTONIAN, GEORGIAN, ICELANDIC, IRISH, LATVIAN,
    LITHUANIAN, MACEDONIAN, MALTESE, MONGOLIAN, WELSH
).map { it.tag }
