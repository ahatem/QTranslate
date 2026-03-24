package com.github.ahatem.qtranslate.ui.swing.main.languagebar

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.LanguageComboBox
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JPanel

class LanguageSelectionBar(
    private val iconManager: IconManager,
    private val onClear: () -> Unit,
    private val onSourceLanguageSelected: (LanguageCode) -> Unit,
    private val onSwap: () -> Unit,
    private val onTargetLanguageSelected: (LanguageCode) -> Unit,
    private val onTranslate: () -> Unit
) : JPanel(GridBagLayout()), Renderable<LanguageSelectionBarState> {

    private val clearButton = createButtonWithIcon(iconManager, "icons/lucide/trash.svg", 16)
    private val sourceLanguageComboBox = LanguageComboBox { lang -> onSourceLanguageSelected(lang) }
    private val swapButton = createButtonWithIcon(iconManager, "icons/lucide/swap.svg", 16)
    private val targetLanguageComboBox = LanguageComboBox { lang -> onTargetLanguageSelected(lang) }
    private val translateButton = JButton()

    init {
        clearButton.addActionListener { onClear() }
        swapButton.addActionListener { onSwap() }
        translateButton.addActionListener { onTranslate() }

        val grid = GridBag(this, horizontalGap = 4)
        grid.defaultFill(GridBagConstraints.BOTH)

        grid.weightX(0.0).add(clearButton)
        grid.weightX(0.5).add(sourceLanguageComboBox)
        grid.weightX(0.0).add(swapButton)
        grid.weightX(0.5).add(targetLanguageComboBox)
        grid.weightX(0.0)
            .fill(GridBagConstraints.NONE)
            .anchor(GridBagConstraints.EAST)
            .add(translateButton)
    }

    override fun render(state: LanguageSelectionBarState) {
        clearButton.isEnabled = !state.isLoading && state.canClear
        clearButton.toolTipText = state.strings.clearTooltip

        swapButton.isEnabled = !state.isLoading && state.canSwap
        swapButton.toolTipText = state.strings.swapTooltip

        translateButton.isEnabled = !state.isLoading
        translateButton.text = state.strings.translateButtonText
        translateButton.toolTipText = state.strings.translateButtonText

        sourceLanguageComboBox.render(
            availableLanguages = state.allSourceLanguages,
            selectedLanguage = state.selectedSourceLanguage,
            autoDetectedLanguage = state.detectedSourceLanguage,
            isEnabled = !state.isLoading
        )

        targetLanguageComboBox.render(
            availableLanguages = state.allTargetLanguages,
            selectedLanguage = state.selectedTargetLanguage,
            autoDetectedLanguage = null,
            isEnabled = !state.isLoading
        )
    }
}