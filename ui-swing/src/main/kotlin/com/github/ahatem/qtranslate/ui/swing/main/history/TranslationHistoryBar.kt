package com.github.ahatem.qtranslate.ui.swing.main.history

import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.ComponentOrientation
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

class TranslationHistoryBar(
    private val iconManager: IconManager,
    private val onBackward: () -> Unit,
    private val onForward: () -> Unit,
    private val onImageTranslate: () -> Unit,
) : JPanel(BorderLayout()), Renderable<TranslationHistoryBarState> {

    private val backwardButton = createButtonWithIcon(iconManager, "icons/lucide/arrow-left.svg", 16)
    private val forwardButton = createButtonWithIcon(iconManager, "icons/lucide/arrow-right.svg", 16)
    private val imageTranslateButton = createButtonWithIcon(iconManager, "icons/lucide/scan-text.svg", 16)

    private val statusLabel = JLabel().apply {
        border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
    }

    private val leftGroup = JPanel(FlowLayout(FlowLayout.LEADING, 2, 0)).apply {
        isOpaque = false
        add(backwardButton)
        add(forwardButton)
        add(statusLabel)
    }

    private val rightGroup = JPanel(FlowLayout(FlowLayout.TRAILING, 2, 0)).apply {
        isOpaque = false
        add(imageTranslateButton)
    }

    init {
        backwardButton.addActionListener { onBackward() }
        forwardButton.addActionListener { onForward() }
        imageTranslateButton.addActionListener { onImageTranslate() }

        add(leftGroup, BorderLayout.LINE_START)
        add(rightGroup, BorderLayout.LINE_END)
    }

    override fun applyComponentOrientation(orientation: ComponentOrientation) {
        super.applyComponentOrientation(orientation)
        leftGroup.applyComponentOrientation(orientation)
        rightGroup.applyComponentOrientation(orientation)
        leftGroup.revalidate()
        rightGroup.revalidate()

        val isRtl = orientation == java.awt.ComponentOrientation.RIGHT_TO_LEFT
        backwardButton.icon = iconManager.getIcon(
            if (isRtl) "icons/lucide/arrow-right.svg" else "icons/lucide/arrow-left.svg",
            16, 16
        )
        forwardButton.icon = iconManager.getIcon(
            if (isRtl) "icons/lucide/arrow-left.svg" else "icons/lucide/arrow-right.svg",
            16, 16
        )
    }

    override fun render(state: TranslationHistoryBarState) {
        statusLabel.text = state.statusText

        backwardButton.isEnabled = !state.isLoading && state.canGoBackward
        forwardButton.isEnabled = !state.isLoading && state.canGoForward
        imageTranslateButton.isEnabled = !state.isLoading

        backwardButton.toolTipText = state.strings.backwardTooltip
        forwardButton.toolTipText = state.strings.forwardTooltip
        imageTranslateButton.toolTipText = state.strings.imageTranslateTooltip
    }
}