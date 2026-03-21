package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import java.awt.GridBagConstraints
import javax.swing.*

class TranslationPanel(private val store: SettingsStore) : SettingsPanel() {

    private val types = listOf(
        ExtraOutputTypeInfo(ExtraOutputType.None,             "None"),
        ExtraOutputTypeInfo(ExtraOutputType.BackwardTranslate,"Backward Translation"),
        ExtraOutputTypeInfo(ExtraOutputType.Summarize,        "Summarization"),
        ExtraOutputTypeInfo(ExtraOutputType.Rewrite,          "Rewriting")
    )

    private lateinit var instantCheck: JCheckBox
    private lateinit var spellCheck:   JCheckBox
    private lateinit var typeCombo:    JComboBox<ExtraOutputTypeInfo>
    private lateinit var useTranslated: JRadioButton
    private lateinit var useInput:      JRadioButton

    init { buildUI() }

    private fun buildUI() {
        // ---- Behavior ----
        addSeparator("Behavior")

        instantCheck = addCheckbox(
            text = "Instant translation (translate as you type)",
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(isInstantTranslationEnabled = enabled) } }
        )
        addHint("Triggers translation automatically after you stop typing.")

        spellCheck = addCheckbox(
            text = "Enable spell checking on input",
            selected = false,
            onChange = { enabled -> applyDraft(store) { it.copy(isSpellCheckingEnabled = enabled) } }
        )

        // ---- Extra Output ----
        addSeparator("Extra Output")

        typeCombo = JComboBox<ExtraOutputTypeInfo>(types.toTypedArray()).apply {
            setRenderer { _, value, _, _, _ -> JLabel(value?.displayName ?: "") }
            addActionListener {
                if (!isUpdatingFromState) {
                    val type = (selectedItem as? ExtraOutputTypeInfo)?.type ?: return@addActionListener
                    applyDraft(store) { it.copy(extraOutputType = type) }
                }
            }
        }
        addRow("Type:", typeCombo)
        addHint("Shows a secondary result panel below the main translation output.")

        // Source radio buttons
        useTranslated = JRadioButton("Use translated text").apply {
            addActionListener {
                if (isSelected && !isUpdatingFromState)
                    applyDraft(store) { it.copy(extraOutputSource = ExtraOutputSource.Output) }
            }
        }
        useInput = JRadioButton("Use input text").apply {
            addActionListener {
                if (isSelected && !isUpdatingFromState)
                    applyDraft(store) { it.copy(extraOutputSource = ExtraOutputSource.Input) }
            }
        }
        ButtonGroup().apply { add(useTranslated); add(useInput) }

        val radioPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(useTranslated)
            add(Box.createVerticalStrut(4))
            add(useInput)
        }
        gb.nextRow().add(JLabel("Source:"))
        gb.weightX(1.0).fill(GridBagConstraints.HORIZONTAL)
            .anchor(GridBagConstraints.WEST).add(radioPanel)

        finishLayout()
    }

    override fun render(state: SettingsState) {
        val c = state.workingConfiguration
        withoutTrigger {
            instantCheck.isSelected = c.isInstantTranslationEnabled
            spellCheck.isSelected   = c.isSpellCheckingEnabled
            typeCombo.selectedItem  = types.find { it.type == c.extraOutputType }

            when (c.extraOutputSource) {
                ExtraOutputSource.Output -> useTranslated.isSelected = true
                ExtraOutputSource.Input  -> useInput.isSelected = true
            }

            val extraEnabled = c.extraOutputType != ExtraOutputType.None
            useTranslated.isEnabled = extraEnabled
            useInput.isEnabled      = extraEnabled
        }
    }

    private data class ExtraOutputTypeInfo(val type: ExtraOutputType, val displayName: String)
}