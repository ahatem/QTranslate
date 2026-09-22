package com.github.ahatem.qtranslate.ui.swing.main.output

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonStatus
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonTranslationResult
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.shared.util.isRTL
import com.github.ahatem.qtranslate.ui.swing.shared.util.toFont
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.UIManager
import javax.swing.border.AbstractBorder

data class ComparisonResultsState(
    val results: List<ComparisonTranslationResult>,
    val title: String,
    val loadingText: String,
    val unavailableText: String,
    val failureText: String,
    val copyLabel: String,
    val fontConfig: FontConfig,
    val fallbackFontConfig: FontConfig,
    val onCopy: (String) -> Unit,
    val showEmptyState: Boolean = false,
    val emptyText: String = "",
    val configureLabel: String = "",
    val onConfigure: () -> Unit = {}
)

/** Comparison cards for the dedicated comparison workspace. */
class ComparisonResultsPanel : JPanel(BorderLayout()) {
    private val titleLabel = JLabel().apply {
        font = font.deriveFont(font.style or java.awt.Font.BOLD)
        border = BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(4), UIScale.scale(10))
    }
    private val cards = ComparisonCardsPanel().apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, UIScale.scale(10), UIScale.scale(8), UIScale.scale(10))
    }
    private val scrollPane = JScrollPane(cards, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER).apply {
        isVisible = false
        isOpaque = false
        viewport.isOpaque = false
        border = BorderFactory.createEmptyBorder()
        isFocusable = false
        verticalScrollBar.isFocusable = false
    }
    private val cardByServiceId = linkedMapOf<String, ComparisonResultCard>()
    private val emptyPanel = JPanel(BorderLayout(0, UIScale.scale(4))).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10))
    }
    private val emptyLabel = JLabel()
    private val configureButton = JButton()

    init {
        isOpaque = false
        isVisible = false
        add(titleLabel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        emptyPanel.add(emptyLabel, BorderLayout.CENTER)
        emptyPanel.add(configureButton, BorderLayout.LINE_END)
        add(emptyPanel, BorderLayout.SOUTH)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { cards.revalidate() }
        })
    }

    fun render(state: ComparisonResultsState) {
        if (state.results.isEmpty()) {
            cardByServiceId.clear()
            while (cards.componentCount > 0) cards.remove(0)
            scrollPane.isVisible = false
            emptyLabel.text = state.emptyText
            configureButton.text = state.configureLabel
            configureButton.isVisible = state.showEmptyState && state.configureLabel.isNotBlank()
            configureButton.actionListeners.forEach(configureButton::removeActionListener)
            configureButton.addActionListener { state.onConfigure() }
            emptyPanel.isVisible = state.showEmptyState && state.emptyText.isNotBlank()
            isVisible = emptyPanel.isVisible
            revalidate(); repaint()
            return
        }
        titleLabel.text = state.title
        emptyPanel.isVisible = false
        val activeIds = state.results.map { it.serviceId }.toSet()
        cardByServiceId.keys.filterNot(activeIds::contains).toList().forEach(cardByServiceId::remove)
        while (cards.componentCount > 0) cards.remove(0)
        state.results.forEachIndexed { index, result ->
            val card = cardByServiceId.getOrPut(result.serviceId) { ComparisonResultCard() }
            card.render(result, state)
            cards.add(card)
            if (index < state.results.lastIndex) cards.add(javax.swing.Box.createVerticalStrut(UIScale.scale(6)))
        }
        val preferred = cards.preferredSize.height + UIScale.scale(2)
        scrollPane.preferredSize = Dimension(0, preferred.coerceAtMost(UIScale.scale(220)).coerceAtLeast(UIScale.scale(1)))
        scrollPane.isVisible = true
        isVisible = true
        revalidate(); repaint()
    }
}

/** Primary output followed by comparison cards when the Comparison layout is selected. */
class ComparisonWorkspacePanel(
    primaryPanel: JPanel,
    comparisonPanel: ComparisonResultsPanel
) : JPanel(BorderLayout()) {
    private val primaryLabel = JLabel().apply {
        border = BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(4), UIScale.scale(10))
        font = font.deriveFont(font.style or java.awt.Font.BOLD)
    }

    init {
        isOpaque = false
        val primarySection = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(primaryLabel, BorderLayout.NORTH)
            add(primaryPanel, BorderLayout.CENTER)
        }
        add(primarySection, BorderLayout.NORTH)
        add(comparisonPanel, BorderLayout.CENTER)
    }

    fun setPrimaryLabel(label: String) { primaryLabel.text = label }
}

private class ComparisonCardsPanel : JPanel(), javax.swing.Scrollable {
    init { layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS) }
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = UIScale.scale(24)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}

private class ComparisonResultCard : JPanel(BorderLayout(0, UIScale.scale(3))) {
    private val textPane = AdvancedTextPane({}, {}, {}).apply {
        isEditable = false
        isOpaque = false
        margin = Insets(UIScale.scale(3), UIScale.scale(4), UIScale.scale(3), UIScale.scale(4))
        isFocusable = true
    }
    private val providerLabel = JLabel()
    private val stateLabel = JLabel()
    private val copyButton = JButton()
    private val header = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(UIScale.scale(5), UIScale.scale(7), 0, UIScale.scale(7))
        add(providerLabel, BorderLayout.CENTER)
        add(stateLabel, BorderLayout.LINE_END)
    }
    private var onCopy: ((String) -> Unit)? = null
    private var bodyComponent: Component? = null

    init {
        isOpaque = true
        background = UIManager.getColor("Panel.background")
        border = ThemeAwareComparisonBorder()
        providerLabel.font = providerLabel.font.deriveFont(providerLabel.font.style or java.awt.Font.BOLD)
        copyButton.putClientProperty("JButton.buttonType", "toolBarButton")
        copyButton.isFocusable = true
        copyButton.addActionListener { onCopy?.invoke(copyButton.actionCommand ?: "") }
    }

    fun render(result: ComparisonTranslationResult, state: ComparisonResultsState) {
        providerLabel.text = result.serviceName ?: state.unavailableText
        stateLabel.text = when (result.status) {
            ComparisonStatus.LOADING -> state.loadingText
            ComparisonStatus.SUCCESS -> ""
            ComparisonStatus.FAILURE -> state.failureText
        }
        onCopy = state.onCopy
        copyButton.text = state.copyLabel
        copyButton.actionCommand = result.text
        copyButton.isEnabled = result.status == ComparisonStatus.SUCCESS && result.text.isNotBlank()
        header.remove(copyButton)
        if (result.status == ComparisonStatus.SUCCESS) header.add(copyButton, BorderLayout.LINE_END)
        bodyComponent?.let { remove(it) }
        bodyComponent = null
        add(header, BorderLayout.NORTH)
        when (result.status) {
            ComparisonStatus.LOADING -> Unit
            ComparisonStatus.SUCCESS -> {
                textPane.componentOrientation = if (result.text.isRTL()) java.awt.ComponentOrientation.RIGHT_TO_LEFT
                    else java.awt.ComponentOrientation.LEFT_TO_RIGHT
                textPane.render(result.text, emptyList(), isEditable = false)
                textPane.updateFontsAndRescanDocument(state.fontConfig.toFont(), state.fallbackFontConfig.toFont())
                add(textPane, BorderLayout.CENTER)
                bodyComponent = textPane
            }
            ComparisonStatus.FAILURE -> add(JLabel(result.errorMessage?.takeIf { it.isNotBlank() } ?: state.failureText).apply {
                foreground = UIManager.getColor("Component.error.focusedBorderColor") ?: UIManager.getColor("Label.foreground")
                border = BorderFactory.createEmptyBorder(0, UIScale.scale(7), UIScale.scale(5), UIScale.scale(7))
            }.also { bodyComponent = it }, BorderLayout.CENTER)
        }
        revalidate(); repaint()
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

private class ThemeAwareComparisonBorder : AbstractBorder() {
    override fun paintBorder(c: Component, g: java.awt.Graphics, x: Int, y: Int, w: Int, h: Int) {
        g.color = UIManager.getColor("Component.borderColor") ?: Color.GRAY
        g.drawRect(x, y, w - 1, h - 1)
    }
    override fun getBorderInsets(c: Component): Insets = Insets(1, 1, 1, 1)
}
