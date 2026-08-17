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
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons

class TranslationHistoryBar(
    private val iconManager: IconManager,
    private val onBackward: () -> Unit,
    private val onForward: () -> Unit,
    private val onImageTranslate: () -> Unit,
    private val onDocumentTranslate: () -> Unit,
) : JPanel(BorderLayout()), Renderable<TranslationHistoryBarState> {

    private val backwardButton = createButtonWithIcon(iconManager, Icons.NAV_BACK, 16)
    private val forwardButton = createButtonWithIcon(iconManager, Icons.NAV_FORWARD, 16)
    private val imageTranslateButton = createButtonWithIcon(iconManager, Icons.OCR, 16)
    private val documentTranslateButton = createButtonWithIcon(iconManager, Icons.DOCUMENT, 16)

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
        add(documentTranslateButton)
    }

    init {
        backwardButton.addActionListener { onBackward() }
        forwardButton.addActionListener { onForward() }
        imageTranslateButton.addActionListener { onImageTranslate() }
        documentTranslateButton.addActionListener { onDocumentTranslate() }

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
            if (isRtl) Icons.NAV_FORWARD else Icons.NAV_BACK,
            16, 16
        )
        forwardButton.icon = iconManager.getIcon(
            if (isRtl) Icons.NAV_BACK else Icons.NAV_FORWARD,
            16, 16
        )
    }

    override fun render(state: TranslationHistoryBarState) {
        statusLabel.text = state.statusText

        backwardButton.isEnabled = !state.isLoading && state.canGoBackward
        forwardButton.isEnabled = !state.isLoading && state.canGoForward
        imageTranslateButton.isEnabled = !state.isLoading
        documentTranslateButton.isEnabled = !state.isLoading

        backwardButton.toolTipText = state.strings.backwardTooltip
        forwardButton.toolTipText = state.strings.forwardTooltip
        imageTranslateButton.toolTipText = state.strings.imageTranslateTooltip
        documentTranslateButton.toolTipText = state.strings.documentTranslateTooltip
    }
}