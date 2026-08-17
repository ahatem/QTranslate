package com.github.ahatem.qtranslate.ui.swing.main.languagebar

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.GridBag
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.LanguageComboBox
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JPanel
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

class LanguageSelectionBar(
    private val iconManager: IconManager,
    private val localizer: LocalizationManager,
    private val onClear: () -> Unit,
    private val onSourceLanguageSelected: (LanguageCode) -> Unit,
    private val onSwap: () -> Unit,
    private val onTargetLanguageSelected: (LanguageCode) -> Unit,
    private val onTranslate: () -> Unit,
    private val onCancel: () -> Unit = {}
) : JPanel(GridBagLayout()), Renderable<LanguageSelectionBarState> {

    private val clearButton = createButtonWithIcon(iconManager, Icons.DELETE, 16)
    private val sourceLanguageComboBox = LanguageComboBox(
        onLanguageSelected = { lang -> onSourceLanguageSelected(lang) },
        localizer = localizer
    )
    private val swapButton = createButtonWithIcon(iconManager, Icons.SWAP, 16)
    private val targetLanguageComboBox = LanguageComboBox(
        onLanguageSelected = { lang -> onTargetLanguageSelected(lang) },
        localizer = localizer
    )
    private val translateButton = JButton()

    // Single listener that delegates to the correct callback depending on mode.
    private var isInCancelMode = false

    init {
        clearButton.addActionListener { onClear() }
        swapButton.addActionListener { onSwap() }
        translateButton.addActionListener { if (isInCancelMode) onCancel() else onTranslate() }

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

        // While loading: morph the Translate button into a Cancel button.
        // The single action listener checks isInCancelMode to route correctly.
        isInCancelMode = state.isLoading
        translateButton.isEnabled = true
        if (state.isLoading) {
            translateButton.text = state.strings.cancelButtonText
            translateButton.toolTipText = state.strings.cancelButtonText
        } else {
            translateButton.text = state.strings.translateButtonText
            translateButton.toolTipText = state.strings.translateButtonText
        }

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