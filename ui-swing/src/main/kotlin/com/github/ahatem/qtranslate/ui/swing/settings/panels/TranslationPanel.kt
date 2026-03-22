package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import java.awt.GridBagConstraints
import javax.swing.*

class TranslationPanel(
    private val store: SettingsStore,
    private val localizationManager: LocalizationManager
) : SettingsPanel() {

    private val types by lazy {
        listOf(
            ExtraOutputTypeInfo(
                ExtraOutputType.None,
                localizationManager.getString("settings_translation.type_none")
            ),
            ExtraOutputTypeInfo(
                ExtraOutputType.BackwardTranslate,
                localizationManager.getString("settings_translation.type_backward")
            ),
            ExtraOutputTypeInfo(
                ExtraOutputType.Summarize,
                localizationManager.getString("settings_translation.type_summarize")
            ),
            ExtraOutputTypeInfo(
                ExtraOutputType.Rewrite,
                localizationManager.getString("settings_translation.type_rewrite")
            )
        )
    }

    private lateinit var instantCheck: JCheckBox
    private lateinit var spellCheck: JCheckBox
    private lateinit var typeCombo: JComboBox<ExtraOutputTypeInfo>
    private lateinit var useTranslated: JRadioButton
    private lateinit var useInput: JRadioButton

    init { buildUI() }

    private fun buildUI() {
        addSeparator(localizationManager.getString("settings_translation.behavior_group"))

        instantCheck = addCheckbox(
            text = localizationManager.getString("settings_translation.instant_translation"),
            selected = false,
            onChange = { enabled ->
                applyDraft(store) { it.copy(isInstantTranslationEnabled = enabled) }
            }
        )
        addHint(localizationManager.getString("settings_translation.instant_hint"))

        spellCheck = addCheckbox(
            text = localizationManager.getString("settings_translation.spell_check_input"),
            selected = false,
            onChange = { enabled ->
                applyDraft(store) { it.copy(isSpellCheckingEnabled = enabled) }
            }
        )

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

        addRow(
            localizationManager.getString("settings_translation.extra_output_type"),
            typeCombo
        )
        addHint(localizationManager.getString("settings_translation.extra_output_hint"))

        useTranslated = JRadioButton(
            localizationManager.getString("settings_translation.source_use_translated")
        ).apply {
            addActionListener {
                if (isSelected && !isUpdatingFromState) {
                    applyDraft(store) { it.copy(extraOutputSource = ExtraOutputSource.Output) }
                }
            }
        }

        useInput = JRadioButton(
            localizationManager.getString("settings_translation.source_use_input")
        ).apply {
            addActionListener {
                if (isSelected && !isUpdatingFromState) {
                    applyDraft(store) { it.copy(extraOutputSource = ExtraOutputSource.Input) }
                }
            }
        }

        ButtonGroup().apply {
            add(useTranslated)
            add(useInput)
        }

        val radioPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(useTranslated)
            add(Box.createVerticalStrut(4))
            add(useInput)
        }

        gb.nextRow().add(
            JLabel(localizationManager.getString("settings_translation.extra_output_source"))
        )
        gb.weightX(1.0)
            .fill(GridBagConstraints.HORIZONTAL)
            .anchor(GridBagConstraints.WEST)
            .add(radioPanel)

        finishLayout()
    }

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            instantCheck.isSelected = c.isInstantTranslationEnabled
            spellCheck.isSelected = c.isSpellCheckingEnabled
            typeCombo.selectedItem = types.find { it.type == c.extraOutputType }

            when (c.extraOutputSource) {
                ExtraOutputSource.Output -> useTranslated.isSelected = true
                ExtraOutputSource.Input -> useInput.isSelected = true
            }

            val extraEnabled = c.extraOutputType != ExtraOutputType.None
            useTranslated.isEnabled = extraEnabled
            useInput.isEnabled = extraEnabled
        }
    }

    private data class ExtraOutputTypeInfo(
        val type: ExtraOutputType,
        val displayName: String
    )
}