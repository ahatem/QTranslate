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
import javax.swing.JTextArea
import javax.swing.UIManager
import javax.swing.border.AbstractBorder

enum class ResultPresentationMode {
    MAIN_WORKSPACE,
    QUICK_POPUP
}

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

/**
 * Reusable comparison-card list. Scrolling belongs to the enclosing workspace or popup so the
 * primary result and comparison cards can share one viewport.
 */
class ComparisonResultsPanel(
    private val presentationMode: ResultPresentationMode = ResultPresentationMode.MAIN_WORKSPACE
) : JPanel(BorderLayout()) {
    private val titleLabel = JLabel().apply {
        font = font.deriveFont(font.style or java.awt.Font.BOLD)
        border = BorderFactory.createEmptyBorder(
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 5 else 8),
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 6 else 10),
            UIScale.scale(4),
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 6 else 10)
        )
    }
    private val cards = ComparisonCardsPanel().apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(
            0,
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 6 else 10),
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 5 else 8),
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 6 else 10)
        )
    }
    private val emptyPanel = JPanel(BorderLayout(0, UIScale.scale(4))).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10))
    }
    private val emptyLabel = JLabel()
    private val configureButton = JButton()
    private val cardByServiceId = linkedMapOf<String, ComparisonResultCard>()

    init {
        isOpaque = false
        isVisible = false
        add(titleLabel, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
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
            titleLabel.isVisible = false
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
        titleLabel.isVisible = state.title.isNotBlank()
        emptyPanel.isVisible = false
        val activeIds = state.results.map { it.serviceId }.toSet()
        cardByServiceId.keys.filterNot(activeIds::contains).toList().forEach(cardByServiceId::remove)

        // Only rebuild the outer ordering when provider order or membership changed. A sibling
        // completion therefore leaves an unchanged card attached, preserving selection/focus.
        val currentIds = cards.components.filterIsInstance<ComparisonResultCard>().map { it.serviceId }
        val desiredIds = state.results.map { it.serviceId }
        if (currentIds != desiredIds) {
            while (cards.componentCount > 0) cards.remove(0)
            state.results.forEachIndexed { index, result ->
                cards.add(cardByServiceId.getOrPut(result.serviceId) { ComparisonResultCard() })
                if (index < state.results.lastIndex) cards.add(javax.swing.Box.createVerticalStrut(UIScale.scale(6)))
            }
        }

        state.results.forEach { result ->
            cardByServiceId.getOrPut(result.serviceId) { ComparisonResultCard() }.render(result, state)
        }
        isVisible = true
        revalidate(); repaint()
    }
}

private class ComparisonCardsPanel : JPanel(), javax.swing.Scrollable {
    init { layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS) }
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = UIScale.scale(24)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}

internal class ComparisonResultCard : JPanel(BorderLayout(0, UIScale.scale(3))) {
    val serviceId: String get() = currentServiceId
    private var currentServiceId = ""
    private var lastRenderKey: RenderKey? = null
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

    fun textPaneForTest(): AdvancedTextPane = textPane

    fun render(result: ComparisonTranslationResult, state: ComparisonResultsState) {
        currentServiceId = result.serviceId
        onCopy = state.onCopy
        val key = RenderKey(
            serviceId = result.serviceId,
            serviceName = result.serviceName,
            status = result.status,
            text = result.text,
            errorMessage = result.errorMessage,
            loadingText = state.loadingText,
            unavailableText = state.unavailableText,
            failureText = state.failureText,
            copyLabel = state.copyLabel,
            fontConfig = state.fontConfig,
            fallbackFontConfig = state.fallbackFontConfig
        )
        if (key == lastRenderKey) return
        lastRenderKey = key

        providerLabel.text = result.serviceName ?: state.unavailableText
        stateLabel.text = when (result.status) {
            ComparisonStatus.LOADING -> state.loadingText
            ComparisonStatus.SUCCESS -> ""
            ComparisonStatus.FAILURE -> state.failureText
        }
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
            ComparisonStatus.FAILURE -> add(JTextArea(
                result.errorMessage?.takeIf { it.isNotBlank() } ?: state.failureText
            ).apply {
                isEditable = false
                isFocusable = true
                lineWrap = true
                wrapStyleWord = true
                isOpaque = false
                foreground = UIManager.getColor("Component.error.focusedBorderColor")
                    ?: UIManager.getColor("Label.foreground")
                border = BorderFactory.createEmptyBorder(0, UIScale.scale(7), UIScale.scale(5), UIScale.scale(7))
            }.also { bodyComponent = it }, BorderLayout.CENTER)
        }
        revalidate(); repaint()
    }

    private data class RenderKey(
        val serviceId: String,
        val serviceName: String?,
        val status: ComparisonStatus,
        val text: String,
        val errorMessage: String?,
        val loadingText: String,
        val unavailableText: String,
        val failureText: String,
        val copyLabel: String,
        val fontConfig: FontConfig,
        val fallbackFontConfig: FontConfig
    )

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

private class ThemeAwareComparisonBorder : AbstractBorder() {
    override fun paintBorder(c: Component, g: java.awt.Graphics, x: Int, y: Int, w: Int, h: Int) {
        g.color = UIManager.getColor("Component.borderColor") ?: Color.GRAY
        g.drawRect(x, y, w - 1, h - 1)
    }
    override fun getBorderInsets(c: Component): Insets = Insets(1, 1, 1, 1)
}
