package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.rewriter.RewriteStyle
import com.github.ahatem.qtranslate.api.summarizer.SummaryLength
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
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
 *
 * Language-specific settings (pinned languages, translation rules, dictionary
 * auto-lookup) live in [LanguagesPanel].
 */
class TranslationPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager
) : SettingsPanel() {

    private val summaryLengths by lazy {
        listOf(
            SummaryLengthInfo(SummaryLength.SHORT,   localizationManager.getString("settings_translation.summary_length_short")),
            SummaryLengthInfo(SummaryLength.MEDIUM,  localizationManager.getString("settings_translation.summary_length_medium")),
            SummaryLengthInfo(SummaryLength.LONG,    localizationManager.getString("settings_translation.summary_length_long"))
        )
    }

    private val rewriteStyles by lazy {
        listOf(
            RewriteStyleInfo(RewriteStyle.FORMAL,     localizationManager.getString("settings_translation.rewrite_style_formal")),
            RewriteStyleInfo(RewriteStyle.CASUAL,     localizationManager.getString("settings_translation.rewrite_style_casual")),
            RewriteStyleInfo(RewriteStyle.CONCISE,    localizationManager.getString("settings_translation.rewrite_style_concise")),
            RewriteStyleInfo(RewriteStyle.DETAILED,   localizationManager.getString("settings_translation.rewrite_style_detailed")),
            RewriteStyleInfo(RewriteStyle.SIMPLIFIED, localizationManager.getString("settings_translation.rewrite_style_simplified"))
        )
    }

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
    private lateinit var summaryLengthCombo:  JComboBox<SummaryLengthInfo>
    private lateinit var rewriteStyleRow:     JPanel
    private lateinit var rewriteStyleCombo:   JComboBox<RewriteStyleInfo>

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
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
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
        summaryLengthCombo = JComboBox<SummaryLengthInfo>(summaryLengths.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val length = (selectedItem as? SummaryLengthInfo)?.length ?: return@addActionListener
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
        rewriteStyleCombo = JComboBox<RewriteStyleInfo>(rewriteStyles.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val style = (selectedItem as? RewriteStyleInfo)?.style ?: return@addActionListener
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

            summaryLengthCombo.selectedItem = summaryLengths.find { it.length == c.summaryLength }
            rewriteStyleCombo.selectedItem  = rewriteStyles.find { it.style == c.rewriteStyle }
            summaryLengthRow.isVisible      = c.extraOutputType == ExtraOutputType.Summarize
            rewriteStyleRow.isVisible       = c.extraOutputType == ExtraOutputType.Rewrite
        }
    }

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

    private data class ExtraOutputTypeInfo(val type: ExtraOutputType, val displayName: String)
    private data class SummaryLengthInfo(val length: SummaryLength, val displayName: String)
    private data class RewriteStyleInfo(val style: RewriteStyle, val displayName: String)
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
