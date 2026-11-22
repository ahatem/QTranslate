package com.github.ahatem.qtranslate.ui.swing.main.languagebar

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.localization.getDisplayName
import com.github.ahatem.qtranslate.ui.swing.main.widgets.ChevronButton
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.LanguageComboBox
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.compactLabel
import java.awt.ComponentOrientation
import java.awt.Cursor
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingConstants

/**
 * The active language pair as one chip ("EN → AR"), opening a popup with the source, swap and
 * target controls.
 *
 * It stands in for the language bar when that is not drawn. The popup's controls are the bar's own
 * kind (a [LanguageComboBox] for each language and the same swap button), filled from the same
 * state by the same rules, and report through the same callbacks, so choosing a language here does
 * exactly what choosing it in the bar does.
 *
 * Right-to-left: the chip is mirrored, and so is the pair. See [formatLanguagePair].
 */
class LanguagePairChip(
    iconManager: IconManager,
    private val localizer: LocalizationManager,
    onSourceLanguageSelected: (LanguageCode) -> Unit,
    onSwap: () -> Unit,
    onTargetLanguageSelected: (LanguageCode) -> Unit
) : ChevronButton(), Renderable<LanguagePairChipState> {

    private var currentState: LanguagePairChipState? = null

    private val controls = LanguagePairControls(
        iconManager, localizer, onSourceLanguageSelected, onSwap, onTargetLanguageSelected
    )
    private val popup = JPopupMenu().apply {
        add(controls)
        isFocusable = true
    }

    init {
        buttonType = ButtonType.toolBarButton
        isFocusable = true
        isRequestFocusEnabled = false
        cursor = Cursor(Cursor.HAND_CURSOR)
        horizontalAlignment = SwingConstants.LEADING
        showChevron = true
        addActionListener { showPopupBelow(popup) }
    }

    override fun render(state: LanguagePairChipState) {
        currentState = state
        controls.render(state)
        refresh()
    }

    /** The pair is written in the direction the chip is laid out in, so it follows the orientation. */
    override fun setComponentOrientation(orientation: ComponentOrientation) {
        super.setComponentOrientation(orientation)
        refresh()
    }

    /** The popup the chip opens, for tests. */
    internal fun popupForTest(): JPopupMenu = popup

    private fun refresh() {
        val state = currentState ?: return
        val bar = state.bar
        val rightToLeft = !componentOrientation.isLeftToRight

        text = formatLanguagePair(
            source = bar.selectedSourceLanguage.compactLabel(bar.detectedSourceLanguage),
            target = bar.selectedTargetLanguage.compactLabel(null),
            rightToLeft = rightToLeft
        )
        toolTipText = state.strings.tooltipFormat.format(
            formatLanguagePair(
                source = fullName(bar.selectedSourceLanguage, bar.detectedSourceLanguage, state.strings.autoDetectLabel),
                target = fullName(bar.selectedTargetLanguage, null, state.strings.autoDetectLabel),
                rightToLeft = rightToLeft
            )
        )
        isEnabled = bar.areLanguagesEditable
        revalidate()
        repaint()
    }

    /** The name as the language combo box words it, so the tooltip and the list agree. */
    private fun fullName(language: LanguageCode, detected: LanguageCode?, autoDetectLabel: String): String =
        if (language == LanguageCode.AUTO && detected != null) "${detected.getDisplayName()} ($autoDetectLabel)"
        else language.getDisplayName(autoDetectLabel = autoDetectLabel)
}

/**
 * The popup's contents: a labelled source box, the swap button and a labelled target box, in a
 * row that mirrors with the interface.
 */
internal class LanguagePairControls(
    iconManager: IconManager,
    localizer: LocalizationManager,
    onSourceLanguageSelected: (LanguageCode) -> Unit,
    onSwap: () -> Unit,
    onTargetLanguageSelected: (LanguageCode) -> Unit
) : JPanel(GridBagLayout()) {

    val sourceComboBox = LanguageComboBox(onLanguageSelected = onSourceLanguageSelected, localizer = localizer)
    val targetComboBox = LanguageComboBox(onLanguageSelected = onTargetLanguageSelected, localizer = localizer)
    val swapButton = createButtonWithIcon(iconManager, Icons.SWAP, 16)
    val sourceLabel = captionLabel()
    val targetLabel = captionLabel()

    init {
        border = BorderFactory.createEmptyBorder(
            UIScale.scale(PADDING), UIScale.scale(PADDING), UIScale.scale(PADDING), UIScale.scale(PADDING)
        )
        swapButton.addActionListener { onSwap() }

        val gap = UIScale.scale(GAP)
        fun cell(column: Int, row: Int, weightX: Double) = GridBagConstraints().apply {
            gridx = column
            gridy = row
            weightx = weightX
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.LINE_START
            insets = Insets(0, if (column == 0) 0 else gap, if (row == 0) UIScale.scale(2) else 0, 0)
        }
        add(sourceLabel, cell(0, 0, 1.0))
        add(targetLabel, cell(2, 0, 1.0))
        add(sourceComboBox, cell(0, 1, 1.0))
        add(swapButton, cell(1, 1, 0.0).apply { fill = GridBagConstraints.NONE })
        add(targetComboBox, cell(2, 1, 1.0))
    }

    fun render(state: LanguagePairChipState) {
        sourceLabel.text = state.strings.sourceLabel
        targetLabel.text = state.strings.targetLabel
        swapButton.toolTipText = state.bar.strings.swapTooltip
        swapButton.isEnabled = state.bar.isSwapActionable
        sourceComboBox.renderSource(state.bar)
        targetComboBox.renderTarget(state.bar)
    }

    private fun captionLabel() = JLabel().apply {
        putClientProperty(FlatClientProperties.STYLE_CLASS, "small")
        putClientProperty(FlatClientProperties.STYLE, "foreground: \$Label.disabledForeground")
    }

    private companion object {
        const val PADDING = 8
        const val GAP = 6
    }
}
