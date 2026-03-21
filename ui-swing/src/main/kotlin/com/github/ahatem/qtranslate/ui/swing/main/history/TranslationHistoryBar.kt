package com.github.ahatem.qtranslate.ui.swing.main.history

import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.util.createButtonWithIcon
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.Renderable
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class TranslationHistoryBar(
    iconManager: IconManager,
    private val onBackward: () -> Unit,
    private val onForward: () -> Unit,
    private val onImageTranslate: () -> Unit,
) : JPanel(BorderLayout()), Renderable<TranslationHistoryBarState> {

    private val backwardButton = createButtonWithIcon(iconManager, "icons/lucide/arrow-left.svg", 16)
    private val forwardButton = createButtonWithIcon(iconManager, "icons/lucide/arrow-right.svg", 16)
    private val statusLabel = JLabel().apply {
        border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
    }
    private val imageTranslateButton = createButtonWithIcon(iconManager, "icons/lucide/scan-text.svg", 16)

    init {
        backwardButton.addActionListener { onBackward() }
        forwardButton.addActionListener { onForward() }
        imageTranslateButton.addActionListener { onImageTranslate() }

        val contentPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }

        contentPanel.add(backwardButton)
        contentPanel.add(forwardButton)
        contentPanel.add(Box.createRigidArea(Dimension(4, 0)))
        contentPanel.add(statusLabel)

        contentPanel.add(Box.createHorizontalGlue())
        contentPanel.add(imageTranslateButton)

        add(contentPanel, BorderLayout.CENTER)
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