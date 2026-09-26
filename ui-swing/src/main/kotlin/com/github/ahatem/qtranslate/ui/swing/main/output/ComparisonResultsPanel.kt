package com.github.ahatem.qtranslate.ui.swing.main.output

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonStatus
import com.github.ahatem.qtranslate.core.main.domain.model.ComparisonTranslationResult
import com.github.ahatem.qtranslate.core.main.domain.model.ServiceInfo
import com.github.ahatem.qtranslate.core.settings.data.FontConfig
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import com.github.ahatem.qtranslate.ui.swing.shared.icon.Icons
import com.github.ahatem.qtranslate.ui.swing.shared.util.isRTL
import com.github.ahatem.qtranslate.ui.swing.shared.util.toFont
import com.github.ahatem.qtranslate.ui.swing.shared.widgets.AdvancedTextPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JSeparator
import javax.swing.JTextArea
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.UIManager

enum class ResultPresentationMode {
    MAIN_WORKSPACE,
    QUICK_POPUP
}

data class ComparisonResultsState(
    val results: List<ComparisonTranslationResult>,
    val loadingText: String,
    val unavailableText: String,
    val failureText: String,
    val copyLabel: String,
    val collapseLabel: String = "Collapse",
    val expandLabel: String = "Expand",
    val fontConfig: FontConfig,
    val fallbackFontConfig: FontConfig,
    val onCopy: (String) -> Unit,
    val providerInfos: Map<String, ServiceInfo> = emptyMap(),
    val showEmptyState: Boolean = false,
    val emptyText: String = "",
    val configureLabel: String = "",
    val onConfigure: () -> Unit = {}
)

/** Secondary result feed. Its parent owns scrolling so all translations share one viewport. */
class ComparisonResultsPanel(
    private val presentationMode: ResultPresentationMode = ResultPresentationMode.MAIN_WORKSPACE,
    private val iconManager: IconManager? = null
) : JPanel(BorderLayout()) {
    private val cards = ComparisonCardsPanel().apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 4 else 8),
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 6 else 10),
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 6 else 10),
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 6 else 10)
        )
    }
    private val emptyPanel = JPanel(BorderLayout(UIScale.scale(8), 0)).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 10 else 16),
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 8 else 12),
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 10 else 16),
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 8 else 12)
        )
    }
    private val emptyLabel = JLabel()
    private val configureButton = JButton()
    private val body = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(cards, BorderLayout.CENTER)
    }
    private val cardByServiceId = linkedMapOf<String, ComparisonResultCard>()

    init {
        isOpaque = false
        emptyPanel.add(emptyLabel, BorderLayout.CENTER)
        emptyPanel.add(configureButton, BorderLayout.LINE_END)
        add(body, BorderLayout.CENTER)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { cards.revalidate() }
        })
    }

    fun render(state: ComparisonResultsState) {
        if (state.results.isEmpty()) {
            while (cards.componentCount > 0) cards.remove(0)
            emptyLabel.text = state.emptyText
            configureButton.text = state.configureLabel
            configureButton.isVisible = state.showEmptyState && state.configureLabel.isNotBlank()
            configureButton.actionListeners.forEach(configureButton::removeActionListener)
            configureButton.addActionListener { state.onConfigure() }
            val showEmpty = state.showEmptyState && state.emptyText.isNotBlank()
            body.removeAll()
            if (showEmpty) body.add(emptyPanel, BorderLayout.CENTER)
            isVisible = showEmpty
            revalidate()
            repaint()
            return
        }

        emptyPanel.isVisible = false
        cards.isVisible = true
        body.removeAll()
        body.add(cards, BorderLayout.CENTER)
        isVisible = true
        val activeIds = state.results.map { it.serviceId }.toSet()
        cardByServiceId.keys.filterNot(activeIds::contains).toList().forEach(cardByServiceId::remove)

        // Provider state changes update cards in place. Only membership/order changes rebuild the
        // feed, so selection, focus, and collapsed state survive a sibling completion.
        val currentIds = cards.components.filterIsInstance<ComparisonResultCard>().map { it.serviceId }
        val desiredIds = state.results.map { it.serviceId }
        if (currentIds != desiredIds) {
            while (cards.componentCount > 0) cards.remove(0)
            state.results.forEachIndexed { index, result ->
                cards.add(cardByServiceId.getOrPut(result.serviceId) {
                    ComparisonResultCard(iconManager, presentationMode)
                })
                if (index < state.results.lastIndex) cards.add(providerDivider())
            }
        }

        state.results.forEach { result ->
            cardByServiceId.getOrPut(result.serviceId) {
                ComparisonResultCard(iconManager, presentationMode)
            }.render(result, state)
        }
        revalidate()
        repaint()
    }

    private fun providerDivider() = JPanel(BorderLayout()).apply {
        isOpaque = false
        maximumSize = Dimension(Int.MAX_VALUE, UIScale.scale(9))
        border = BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(12), UIScale.scale(4), UIScale.scale(12))
        add(JSeparator(), BorderLayout.CENTER)
    }
}

private class ComparisonCardsPanel : JPanel(), Scrollable {
    init { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = UIScale.scale(24)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}

internal class ComparisonResultCard(
    private val iconManager: IconManager?,
    private val presentationMode: ResultPresentationMode
) : JPanel(BorderLayout(0, UIScale.scale(3))) {
    val serviceId: String get() = currentServiceId
    private var currentServiceId = ""
    private var lastRenderKey: RenderKey? = null
    private var isExpanded = true
    private var bodyComponent: Component? = null
    private var initialized = false

    private val providerIcon = JLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        preferredSize = Dimension(UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 18 else 20), 0)
    }
    private val providerLabel = JLabel().apply {
        font = font.deriveFont(font.style or java.awt.Font.BOLD)
    }
    private val stateLabel = JLabel()
    private val headerIdentity = JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(6), 0)).apply {
        isOpaque = false
        add(providerIcon)
        add(providerLabel)
        add(stateLabel)
    }
    private val copyButton = createActionButton(Icons.COPY)
    private val collapseButton = JButton().apply {
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
        addActionListener {
            isExpanded = !isExpanded
            updateCollapseButton()
            updateBodyVisibility()
            revalidate()
            repaint()
        }
    }
    private val loadingIndicator = JProgressBar().apply {
        isIndeterminate = true
        isBorderPainted = false
        isOpaque = false
        preferredSize = Dimension(UIScale.scale(16), UIScale.scale(16))
        minimumSize = preferredSize
        maximumSize = preferredSize
    }
    private val headerActions = JPanel(FlowLayout(FlowLayout.TRAILING, 0, 0)).apply {
        isOpaque = false
        add(loadingIndicator)
        add(copyButton)
        add(collapseButton)
    }
    private val header = JPanel(BorderLayout()).apply {
        isOpaque = false
        val row = JPanel(BorderLayout(UIScale.scale(8), 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(
                UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 6 else 8),
                UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 9 else 12),
                UIScale.scale(4),
                UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 9 else 12)
            )
            add(headerIdentity, BorderLayout.LINE_START)
            add(headerActions, BorderLayout.LINE_END)
        }
        add(row, BorderLayout.NORTH)
        add(JSeparator(), BorderLayout.SOUTH)
    }
    private val textPane = AdvancedTextPane({}, {}, {}).apply {
        isEditable = false
        isOpaque = false
        margin = Insets(
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 4 else 6),
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 9 else 12),
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 7 else 10),
            UIScale.scale(if (presentationMode == ResultPresentationMode.QUICK_POPUP) 9 else 12)
        )
        isFocusable = true
    }
    private var onCopy: ((String) -> Unit)? = null

    init {
        isOpaque = false
        add(header, BorderLayout.NORTH)
        copyButton.addActionListener { onCopy?.invoke(copyButton.actionCommand ?: "") }
        updateCollapseButton()
        initialized = true
    }

    fun textPaneForTest(): AdvancedTextPane = textPane

    internal fun collapseForTest() {
        if (isExpanded) collapseButton.doClick()
    }

    fun render(result: ComparisonTranslationResult, state: ComparisonResultsState) {
        currentServiceId = result.serviceId
        onCopy = state.onCopy
        val info = state.providerInfos[result.serviceId]
        val key = RenderKey(
            serviceId = result.serviceId,
            serviceName = info?.name ?: result.serviceName,
            iconPath = info?.iconPath,
            status = result.status,
            text = result.text,
            errorMessage = result.errorMessage,
            loadingText = state.loadingText,
            unavailableText = state.unavailableText,
            failureText = state.failureText,
            copyLabel = state.copyLabel,
            collapseLabel = state.collapseLabel,
            expandLabel = state.expandLabel,
            fontConfig = state.fontConfig,
            fallbackFontConfig = state.fallbackFontConfig
        )
        if (key == lastRenderKey) return
        lastRenderKey = key

        providerLabel.text = info?.name ?: result.serviceName ?: state.unavailableText
        providerIcon.icon = info?.iconPath?.let { path ->
            iconManager?.getIcon(info.id, path, UIScale.scale(18), UIScale.scale(18))
        }
        providerIcon.isVisible = providerIcon.icon != null
        stateLabel.text = when (result.status) {
            ComparisonStatus.LOADING -> state.loadingText
            ComparisonStatus.SUCCESS -> ""
            ComparisonStatus.FAILURE -> state.failureText
        }
        stateLabel.foreground = if (result.status == ComparisonStatus.FAILURE) {
            UIManager.getColor("Component.error.focusedBorderColor") ?: UIManager.getColor("Label.disabledForeground")
        } else {
            UIManager.getColor("Label.disabledForeground")
        }
        copyButton.actionCommand = result.text
        copyButton.toolTipText = state.copyLabel
        copyButton.isVisible = result.status == ComparisonStatus.SUCCESS && result.text.isNotBlank()
        loadingIndicator.isVisible = result.status == ComparisonStatus.LOADING
        collapseButton.toolTipText = if (isExpanded) state.collapseLabel else state.expandLabel

        bodyComponent?.let(::remove)
        bodyComponent = when (result.status) {
            ComparisonStatus.LOADING -> JPanel(FlowLayout(FlowLayout.LEADING)).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(0, UIScale.scale(12), UIScale.scale(10), UIScale.scale(12))
                add(JLabel(state.loadingText).apply {
                    foreground = UIManager.getColor("Label.disabledForeground")
                })
            }
            ComparisonStatus.SUCCESS -> textPane.apply {
                componentOrientation = if (result.text.isRTL()) java.awt.ComponentOrientation.RIGHT_TO_LEFT
                else java.awt.ComponentOrientation.LEFT_TO_RIGHT
                render(result.text, emptyList(), isEditable = false)
                updateFontsAndRescanDocument(state.fontConfig.toFont(), state.fallbackFontConfig.toFont())
            }
            ComparisonStatus.FAILURE -> JTextArea(
                result.errorMessage?.takeIf { it.isNotBlank() } ?: state.failureText
            ).apply {
                isEditable = false
                isFocusable = true
                lineWrap = true
                wrapStyleWord = true
                isOpaque = false
                foreground = UIManager.getColor("Label.foreground")
                border = BorderFactory.createEmptyBorder(
                    0,
                    UIScale.scale(12),
                    UIScale.scale(10),
                    UIScale.scale(12)
                )
            }
        }
        add(bodyComponent, BorderLayout.CENTER)
        updateBodyVisibility()
        revalidate()
        repaint()
    }

    private fun createActionButton(iconPath: String): JButton = JButton().apply {
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        putClientProperty(FlatClientProperties.BUTTON_TYPE, "toolBarButton")
        icon = iconManager?.getIcon(iconPath, UIScale.scale(16), UIScale.scale(16))
    }

    private fun updateCollapseButton() {
        collapseButton.icon = UIManager.getIcon(if (isExpanded) "Tree.expandedIcon" else "Tree.collapsedIcon")
    }

    private fun updateBodyVisibility() {
        bodyComponent?.isVisible = isExpanded
        collapseButton.isVisible = true
    }

    override fun updateUI() {
        super.updateUI()
        if (initialized) {
            updateCollapseButton()
        }
    }

    private data class RenderKey(
        val serviceId: String,
        val serviceName: String?,
        val iconPath: String?,
        val status: ComparisonStatus,
        val text: String,
        val errorMessage: String?,
        val loadingText: String,
        val unavailableText: String,
        val failureText: String,
        val copyLabel: String,
        val collapseLabel: String,
        val expandLabel: String,
        val fontConfig: FontConfig,
        val fallbackFontConfig: FontConfig
    )

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}
