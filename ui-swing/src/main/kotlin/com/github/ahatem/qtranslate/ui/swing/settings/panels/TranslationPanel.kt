package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import java.awt.*
import javax.swing.*

class TranslationPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager
) : SettingsPanel() {

    private val types by lazy {
        listOf(
            ExtraOutputTypeInfo(ExtraOutputType.None,             localizationManager.getString("settings_translation.type_none")),
            ExtraOutputTypeInfo(ExtraOutputType.BackwardTranslate,localizationManager.getString("settings_translation.type_backward")),
            ExtraOutputTypeInfo(ExtraOutputType.Summarize,        localizationManager.getString("settings_translation.type_summarize")),
            ExtraOutputTypeInfo(ExtraOutputType.Rewrite,          localizationManager.getString("settings_translation.type_rewrite"))
        )
    }

    private lateinit var instantCheck:          JCheckBox
    private lateinit var spellCheck:            JCheckBox
    private lateinit var removeLineBreaksCheck: JCheckBox
    private lateinit var typeCombo:             JComboBox<ExtraOutputTypeInfo>
    private lateinit var useTranslated:         JRadioButton
    private lateinit var useInput:              JRadioButton

    // Pinned languages — individual JCheckBoxes, NOT a JList.
    //
    // Why not JList with MULTIPLE_INTERVAL_SELECTION?
    // JList.selectedIndices interacts badly with MVI render():
    // applyDraft() → UpdateDraft → state update (async via coroutine) → render()
    // → withoutTrigger { clearSelection(); selectedIndices = [...] }
    // By the time render() runs, isUpdatingFromState is already false (the coroutine
    // arrived after the withoutTrigger block exited), so clearSelection() fires the
    // ListSelectionListener, which calls applyDraft() again, creating a loop that
    // resets the selection to a single item every time.
    //
    // Individual JCheckBoxes have no shared selection model — each one fires its own
    // ActionListener independently. withoutTrigger correctly guards them because the
    // ActionListener checks isUpdatingFromState synchronously in the same call.
    private val languageCheckBoxes = mutableListOf<Pair<String, JCheckBox>>()

    init { buildUI() }

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

        // ---- Extra output ----
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

        // ---- Pinned languages ----
        addSeparator(localizationManager.getString("settings_translation.pinned_languages_group"))
        addHint(localizationManager.getString("settings_translation.pinned_languages_hint"))

        val checkBoxPanel = JPanel().apply {
            layout   = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(4, 8, 4, 4)
        }

        COMMON_LANGUAGES.forEach { code ->
            val name = localizedName(code)
            val cb   = JCheckBox("$name  ($code)").apply {
                isOpaque = false
                addActionListener {
                    if (!isUpdatingFromState) {
                        // Collect ALL checked codes and save at once
                        val selected = languageCheckBoxes
                            .filter { (_, box) -> box.isSelected }
                            .map    { (c, _)   -> c }
                        applyDraft(store) { it.copy(pinnedLanguages = selected) }
                    }
                }
            }
            languageCheckBoxes.add(code to cb)
            checkBoxPanel.add(cb)
            checkBoxPanel.add(Box.createVerticalStrut(1))
        }

        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0)
            .add(JScrollPane(checkBoxPanel).apply {
                preferredSize             = Dimension(580, 200)
                verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                border = BorderFactory.createLineBorder(
                    UIManager.getColor("Component.borderColor") ?: Color.GRAY
                )
                verticalScrollBar.unitIncrement = 16
            })

        val clearBtn = JButton(localizationManager.getString("settings_translation.pinned_languages_clear")).apply {
            addActionListener {
                languageCheckBoxes.forEach { (_, cb) -> cb.isSelected = false }
                applyDraft(store) { it.copy(pinnedLanguages = emptyList()) }
            }
        }
        gb.nextRow().spanLine().weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .insets(4, 0, 0, 0)
            .add(JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
                isOpaque = false
                add(clearBtn)
            })

        finishLayout()
    }

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            instantCheck.isSelected          = c.isInstantTranslationEnabled
            spellCheck.isSelected            = c.isSpellCheckingEnabled
            removeLineBreaksCheck.isSelected = c.isRemoveLineBreaksEnabled
            typeCombo.selectedItem           = types.find { it.type == c.extraOutputType }

            when (c.extraOutputSource) {
                ExtraOutputSource.Output -> useTranslated.isSelected = true
                ExtraOutputSource.Input  -> useInput.isSelected      = true
            }
            val extraEnabled = c.extraOutputType != ExtraOutputType.None
            useTranslated.isEnabled = extraEnabled
            useInput.isEnabled      = extraEnabled

            // Sync checkboxes — direct assignment, no selection model, no events fired
            // (isUpdatingFromState = true, so ActionListeners are all guarded)
            // Empty pinnedLanguages = no filter active = nothing checked
            languageCheckBoxes.forEach { (code, cb) ->
                cb.isSelected = code in c.pinnedLanguages
            }
        }
    }

    private fun localizedName(code: String): String {
        val locale = runCatching { java.util.Locale.forLanguageTag(code) }.getOrNull()
        val name   = locale?.getDisplayLanguage(java.util.Locale.ENGLISH)
        return if (!name.isNullOrBlank() && name != code) name else code
    }

    private data class ExtraOutputTypeInfo(val type: ExtraOutputType, val displayName: String)

    companion object {
        private val COMMON_LANGUAGES = listOf(
            "en", "ar", "zh", "zh-TW", "hi", "es", "fr", "de", "ja", "ko",
            "pt", "ru", "it", "nl", "pl", "tr", "uk", "vi", "th", "fa",
            "he", "id", "ms", "sv", "da", "fi", "no", "cs", "ro", "hu",
            "bg", "el", "sk", "hr", "ca", "sr"
        )
    }
}