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
    val onCopy: (String) -> Unit
)

class ComparisonResultsPanel : JPanel(BorderLayout()) {

    private val titleLabel = JLabel().apply {
        font = font.deriveFont(font.style or java.awt.Font.BOLD)
        border = BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(4), UIScale.scale(10))
    }
    private val cards = ComparisonCardsPanel().apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, UIScale.scale(10), UIScale.scale(8), UIScale.scale(10))
    }
    private val scrollPane = JScrollPane(
        cards,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    ).apply {
        isVisible = false
        isOpaque = false
        viewport.isOpaque = false
        border = BorderFactory.createEmptyBorder()
        isFocusable = false
        verticalScrollBar.isFocusable = false
    }

    init {
        isOpaque = false
        isVisible = false
        add(titleLabel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                cards.revalidate()
            }
        })
    }

    fun render(state: ComparisonResultsState) {
        val visible = state.results.isNotEmpty()
        if (!visible) {
            if (isVisible) {
                isVisible = false
                revalidate()
                repaint()
            }
            return
        }

        titleLabel.text = state.title
        cards.removeAll()
        state.results.forEachIndexed { index, result ->
            cards.add(
                ComparisonResultCard(
                    result = result,
                    loadingText = state.loadingText,
                    unavailableText = state.unavailableText,
                    failureText = state.failureText,
                    copyLabel = state.copyLabel,
                    fontConfig = state.fontConfig,
                    fallbackFontConfig = state.fallbackFontConfig,
                    onCopy = state.onCopy
                )
            )
            if (index < state.results.lastIndex) {
                cards.add(javax.swing.Box.createVerticalStrut(UIScale.scale(6)))
            }
        }
        updateScrollHeight()
        scrollPane.isVisible = true
        isVisible = true
        revalidate()
        repaint()
    }

    private fun updateScrollHeight() {
        val preferred = cards.preferredSize.height + UIScale.scale(2)
        val maximum = UIScale.scale(220)
        val height = preferred.coerceAtMost(maximum).coerceAtLeast(UIScale.scale(1))
        scrollPane.preferredSize = Dimension(0, height)
    }
}

private class ComparisonCardsPanel : JPanel(), javax.swing.Scrollable {
    init {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = UIScale.scale(24)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}

private class ComparisonResultCard(
    private val result: ComparisonTranslationResult,
    private val loadingText: String,
    private val unavailableText: String,
    private val failureText: String,
    copyLabel: String,
    fontConfig: FontConfig,
    fallbackFontConfig: FontConfig,
    onCopy: (String) -> Unit
) : JPanel(BorderLayout(0, UIScale.scale(3))) {

    private val textPane = AdvancedTextPane({}, {}, {}).apply {
        isEditable = false
        isOpaque = false
        margin = Insets(UIScale.scale(3), UIScale.scale(4), UIScale.scale(3), UIScale.scale(4))
        isFocusable = true
    }

    init {
        isOpaque = true
        background = UIManager.getColor("Panel.background")
        border = ThemeAwareComparisonBorder()

        val providerLabel = JLabel(result.serviceName ?: unavailableText).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(UIScale.scale(5), UIScale.scale(7), 0, UIScale.scale(7))
            add(providerLabel, BorderLayout.CENTER)
        }

        when (result.status) {
            ComparisonStatus.LOADING -> {
                header.add(JLabel(loadingText), BorderLayout.LINE_END)
                add(header, BorderLayout.NORTH)
            }
            ComparisonStatus.SUCCESS -> {
                val copyButton = JButton(copyLabel).apply {
                    putClientProperty("JButton.buttonType", "toolBarButton")
                    isFocusable = true
                    isEnabled = result.text.isNotBlank()
                    addActionListener { onCopy(result.text) }
                }
                header.add(copyButton, BorderLayout.LINE_END)
                add(header, BorderLayout.NORTH)
                textPane.componentOrientation = if (result.text.isRTL()) {
                    java.awt.ComponentOrientation.RIGHT_TO_LEFT
                } else {
                    java.awt.ComponentOrientation.LEFT_TO_RIGHT
                }
                textPane.render(result.text, emptyList(), isEditable = false)
                textPane.updateFontsAndRescanDocument(fontConfig.toFont(), fallbackFontConfig.toFont())
                add(textPane, BorderLayout.CENTER)
            }
            ComparisonStatus.FAILURE -> {
                header.add(JLabel(failureText), BorderLayout.LINE_END)
                add(header, BorderLayout.NORTH)
                add(JLabel(result.errorMessage?.takeIf { it.isNotBlank() } ?: failureText).apply {
                    foreground = UIManager.getColor("Component.error.focusedBorderColor")
                        ?: UIManager.getColor("Label.foreground")
                    border = BorderFactory.createEmptyBorder(0, UIScale.scale(7), UIScale.scale(5), UIScale.scale(7))
                }, BorderLayout.CENTER)
            }
        }
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
