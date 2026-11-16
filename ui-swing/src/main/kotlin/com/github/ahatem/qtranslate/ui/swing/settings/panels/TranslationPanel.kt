package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation
import com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.api.plugin.StandardOptions
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.ServiceOptionChoice
import com.github.ahatem.qtranslate.ui.swing.shared.util.TranslationCapabilities
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
    private val localizationManager: LocalizationManager,
    /**
     * Which optional recipients are usable right now. Evaluated at render time so the page follows
     * plugin changes made in the Plugin Manager, instead of freezing what was installed when the
     * dialog opened.
     */
    private val translationCapabilities: () -> TranslationCapabilities = { TranslationCapabilities.UNKNOWN },
    /** Opens Services & Presets, where a missing summarizer or rewriter is set up. */
    private val onOpenServices: () -> Unit = {},
) : SettingsPanel() {

    private lateinit var dictAutoLookupCheck: JCheckBox
    private lateinit var dictAutoUseTranslated: JRadioButton
    private lateinit var dictAutoUseSource: JRadioButton
    private lateinit var dictionaryPanelCheck: JCheckBox

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

    private val autoCopyModes by lazy {
        listOf(
            AutoCopyTranslationInfo(AutoCopyTranslation.OFF,                localizationManager.getString("settings_translation.auto_copy_off")),
            AutoCopyTranslationInfo(AutoCopyTranslation.QUICK_TRANSLATE_ONLY, localizationManager.getString("settings_translation.auto_copy_quick")),
            AutoCopyTranslationInfo(AutoCopyTranslation.ALL,                localizationManager.getString("settings_translation.auto_copy_all")),
        )
    }

    private lateinit var instantCheck:        JCheckBox
    private lateinit var spellCheck:          JCheckBox
    private lateinit var removeLineBreaksCheck: JCheckBox
    private lateinit var typeCombo:           JComboBox<ExtraOutputTypeInfo>
    private lateinit var useTranslated:       JRadioButton
    private lateinit var useInput:            JRadioButton
    private lateinit var autoCopyCombo:       JComboBox<AutoCopyTranslationInfo>

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

        autoCopyCombo = JComboBox<AutoCopyTranslationInfo>(autoCopyModes.toTypedArray()).apply {
            renderer = DisplayValueRenderer<AutoCopyTranslationInfo>(text = { it?.displayName.orEmpty() })
            addActionListener {
                if (!isUpdatingFromState) {
                    val mode = (selectedItem as? AutoCopyTranslationInfo)?.mode ?: return@addActionListener
                    applyDraft(store) { it.copy(autoCopyTranslation = mode) }
                }
            }
        }
        addRow(localizationManager.getString("settings_translation.auto_copy_translation"), autoCopyCombo)
        addHint(localizationManager.getString("settings_translation.auto_copy_hint"))

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
            renderer = DisplayValueRenderer<ExtraOutputTypeInfo>(
                text = { info ->
                    if (info != null && typeUnavailable(info.type)) unavailableLabel(info.type)
                    else info?.displayName.orEmpty()
                },
                // A capability the application cannot perform is not a live choice. It stays
                // visible and explains itself, mirroring how the layout picker treats an
                // unavailable Comparison, rather than being selected and failing later.
                isDisabled = { info -> info != null && typeUnavailable(info.type) },
                tooltip = { info -> if (info != null && typeUnavailable(info.type)) unavailableHint() else null },
            )
            addActionListener {
                if (isUpdatingFromState) return@addActionListener
                val type = (selectedItem as? ExtraOutputTypeInfo)?.type ?: return@addActionListener
                if (typeUnavailable(type)) {
                    // Clicking a disabled choice is not wasted: it reads as "take me to fix this".
                    withoutTrigger {
                        selectedItem = types.find {
                            it.type == store.state.value.workingConfiguration.extraOutputType
                        }
                    }
                    onOpenServices()
                    return@addActionListener
                }
                applyDraft(store) { it.copy(extraOutputType = type) }
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
        // The summary and rewrite vocabularies are the application's own names for options; a
        // service applies the ones it declares, which is why the extra-output pane can offer more.
        addHint(localizationManager.getString("settings_translation.standard_options_hint"))

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
                val useSource = dictAutoUseSource.isSelected
                applyDraft(store) {
                    it.copy(
                        dictionaryAutoSource = if (enabled) {
                            if (useSource) DictionaryAutoSource.SOURCE else DictionaryAutoSource.TRANSLATED
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

        // Moved here from the Window page: the dictionary panel is dictionary behaviour rather than
        // window chrome, and it belongs with the automatic-lookup controls that decide when a
        // definition appears at all. Applying it opens or closes the dock without a restart.
        dictionaryPanelCheck = addCheckbox(
            text = localizationManager.getString("settings_window.show_dictionary_panel"),
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(showDictionaryPanel = enabled) } }
        )

        // "Show floating popup for single words" used to sit here, indented under the checkbox as
        // if it were its own presentation choice. It never controlled a popup: the coordinator read
        // it as the master gate for every kind of automatic lookup, so unchecking it silently
        // disabled inline and docked lookup too, while checking it did nothing visible unless the
        // main checkbox happened to be on. The popup itself has not opened automatically since
        // 1.4.0 ("The dictionary popup no longer opens by itself whenever a single word is
        // translated"), and it still opens on request with its own hotkey. The control that
        // promised the removed behaviour is gone; Configuration.isDictionaryAutoPopupEnabled stays
        // in the file so old configurations keep loading unchanged.
        finishLayout()
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            instantCheck.isSelected        = c.isInstantTranslationEnabled
            autoCopyCombo.selectedItem     = autoCopyModes.find { it.mode == c.autoCopyTranslation }
            spellCheck.isSelected          = c.isSpellCheckingEnabled
            removeLineBreaksCheck.isSelected = c.isRemoveLineBreaksEnabled
            typeCombo.selectedItem         = types.find { it.type == c.extraOutputType }

            when (c.extraOutputSource) {
                ExtraOutputSource.Output -> useTranslated.isSelected = true
                ExtraOutputSource.Input  -> useInput.isSelected      = true
            }
            val extraEnabled = c.extraOutputType != ExtraOutputType.None
            val backward = c.extraOutputType == ExtraOutputType.BackwardTranslate
            useTranslated.isEnabled = extraEnabled
            // Backward translation always produces the source language and reads the target text,
            // so its own source choice never reaches the runtime. Offering it configured something
            // that could not happen; it is disabled and says why.
            useInput.isEnabled = extraEnabled && !backward
            useInput.toolTipText = if (backward) {
                localizationManager.getString("settings_translation.backward_input_unavailable")
            } else {
                null
            }
            if (backward) useTranslated.isSelected = true

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
            dictionaryPanelCheck.isSelected = c.showDictionaryPanel
        }
    }

    // -------------------------------------------------------------------------
    // Capability checks
    // -------------------------------------------------------------------------

    /** Whether the application can currently run this extra output at all. */
    private fun typeUnavailable(type: ExtraOutputType): Boolean = when (type) {
        ExtraOutputType.Summarize -> !translationCapabilities().summarizerAvailable
        ExtraOutputType.Rewrite -> !translationCapabilities().rewriterAvailable
        else -> false
    }

    /** The unavailable entry's own text: an action, not just a dimmed label. */
    private fun unavailableLabel(type: ExtraOutputType): String =
        localizationManager.getString(
            if (type == ExtraOutputType.Summarize) {
                "settings_translation.type_summarize_unavailable"
            } else {
                "settings_translation.type_rewrite_unavailable"
            }
        )

    private fun unavailableHint(): String =
        localizationManager.getString("settings_translation.type_unavailable_hint")

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

    private data class ExtraOutputTypeInfo(val type: ExtraOutputType, val displayName: String)

    private data class AutoCopyTranslationInfo(val mode: AutoCopyTranslation, val displayName: String)

    // Read access for tests, following the same convention as the OCR page's accessors.
    internal fun typeComboForTest(): JComboBox<*> = typeCombo
    internal fun useInputForTest(): JRadioButton = useInput
    internal fun useTranslatedForTest(): JRadioButton = useTranslated
}
