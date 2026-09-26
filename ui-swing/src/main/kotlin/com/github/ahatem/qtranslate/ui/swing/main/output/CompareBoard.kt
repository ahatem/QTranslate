package com.github.ahatem.qtranslate.ui.swing.main.output

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorPopupButton
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.LayoutManager
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.UIManager

data class CompareBoardState(
    val primary: TranslationProviderState,
    val secondaries: List<TranslationProviderState>,
    val showNoProviders: Boolean = false,
    val noProvidersText: String = "",
    val configureLabel: String = "",
    val onConfigure: () -> Unit = {}
)

/**
 * One scrolling result viewport: the primary provider followed by its comparisons.
 *
 * Narrow boards stack every provider in a single column. Wide boards keep the
 * primary full width and arrange secondaries in two reading-order columns with
 * natural heights. Column placement follows the interface direction while the
 * provider configuration order never changes.
 */
class CompareBoard(
    primarySelector: TranslatorPopupButton? = null,
    iconManager: IconManager? = null
) : JPanel(), Scrollable {

    private val iconManagerRef = iconManager
    private val primaryView = TranslationProviderView(iconManagerRef, primarySelector)
    private val secondaryViews = linkedMapOf<String, TranslationProviderView>()

    private val noProvidersLabel = JLabel()
    private val configureButton = JButton().apply {
        putClientProperty("JButton.buttonType", "toolBarButton")
        isFocusable = true
    }
    private val noProvidersPanel = JPanel(FlowLayout(FlowLayout.LEADING, UIScale.scale(4), 0)).apply {
        isOpaque = false
        isVisible = false
        add(noProvidersLabel)
        add(configureButton)
    }

    private val boardLayout = CompareBoardLayout()
    private var lastOuterPad = -1

    val primaryProviderView: TranslationProviderView get() = primaryView

    init {
        isOpaque = false
        layout = boardLayout
        add(primaryView)
        add(noProvidersPanel)
        border = BorderFactory.createEmptyBorder(
            UIScale.scale(8), UIScale.scale(8), UIScale.scale(8), UIScale.scale(8)
        )
    }

    fun render(state: CompareBoardState) {
        quickMode = state.primary.presentation == ProviderPresentation.QUICK
        primaryView.render(state.primary)
        if (primaryView.parent !== this) add(primaryView, 0)

        val desiredIds = state.secondaries.map { it.serviceId }
        secondaryViews.keys.filterNot(desiredIds::contains).forEach { id ->
            secondaryViews.remove(id)?.let(::remove)
        }
        state.secondaries.forEach { providerState ->
            val view = secondaryViews.getOrPut(providerState.serviceId) {
                TranslationProviderView(iconManagerRef).also(::add)
            }
            view.render(providerState)
        }
        // Configuration order decides the visual order; re-adding moves existing
        // instances without recreating them, so selection and focus survive.
        state.secondaries.forEach { providerState ->
            secondaryViews[providerState.serviceId]?.let { view ->
                remove(view)
                add(view)
            }
        }
        if (primaryView.parent === this) {
            remove(primaryView)
            add(primaryView, 0)
        }

        noProvidersPanel.isVisible = state.showNoProviders
        if (state.showNoProviders) {
            noProvidersLabel.text = state.noProvidersText
            noProvidersLabel.foreground = UIManager.getColor("Label.disabledForeground")
            configureButton.text = state.configureLabel
            configureButton.isVisible = state.configureLabel.isNotBlank()
            configureButton.actionListeners.forEach(configureButton::removeActionListener)
            configureButton.addActionListener { state.onConfigure() }
            if (noProvidersPanel.parent !== this) add(noProvidersPanel)
        }

        updateOuterPadding()
        revalidate()
        repaint()
    }

    fun secondaryViewForTest(serviceId: String): TranslationProviderView? = secondaryViews[serviceId]

    fun orderedServiceIdsForTest(): List<String> = components
        .filterIsInstance<TranslationProviderView>()
        .map { it.serviceId }

    fun isWideForTest(): Boolean = boardLayout.wideMode

    fun noProvidersVisibleForTest(): Boolean = noProvidersPanel.isVisible

    private fun updateOuterPadding() {
        val pad = when {
            quickMode -> 6
            boardLayout.wideMode || width >= UIScale.scale(TranslationProviderView.MEDIUM_BOARD_MIN) -> 16
            else -> 8
        }
        if (pad != lastOuterPad) {
            lastOuterPad = pad
            border = BorderFactory.createEmptyBorder(
                UIScale.scale(pad), UIScale.scale(pad), UIScale.scale(pad), UIScale.scale(pad)
            )
        }
    }

    override fun doLayout() {
        updateOuterPadding()
        super.doLayout()
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        UIScale.scale(24)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        visibleRect.height

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false

    private inner class CompareBoardLayout : LayoutManager {
        var wideMode = false

        override fun addLayoutComponent(name: String?, comp: Component?) = Unit
        override fun removeLayoutComponent(comp: Component?) = Unit

        override fun preferredLayoutSize(parent: Container): Dimension {
            val width = parent.width.takeIf { it > 0 } ?: UIScale.scale(640)
            return measure(parent, width)
        }

        override fun minimumLayoutSize(parent: Container): Dimension = Dimension(0, 0)

        override fun layoutContainer(parent: Container) {
            val insets = parent.insets
            val width = parent.width - insets.left - insets.right
            if (width <= 0) return
            val singleColumn = isSingleColumn()
            if (!singleColumn) {
                if (!wideMode && width >= UIScale.scale(TranslationProviderView.WIDE_BOARD_MIN)) {
                    wideMode = true
                } else if (wideMode && width < UIScale.scale(TranslationProviderView.WIDE_BOARD_RELEASE)) {
                    wideMode = false
                }
            } else if (wideMode) {
                wideMode = false
            }

            var y = insets.top
            val contentWidth = width
            val gap = UIScale.scale(if (quickMode) 4 else 8)

            primaryView.setSize(contentWidth, Int.MAX_VALUE)
            val primaryHeight = primaryView.preferredSize.height
            primaryView.setBounds(insets.left, y, contentWidth, primaryHeight)
            y += primaryHeight + gap

            val visibles = parent.components.filter { it.isVisible && it !== primaryView && it !== noProvidersPanel }
            if (singleColumn || !wideMode || visibles.size < 2) {
                visibles.forEach { view ->
                    view.setSize(contentWidth, Int.MAX_VALUE)
                    val height = view.preferredSize.height
                    view.setBounds(insets.left, y, contentWidth, height)
                    y += height + gap
                }
            } else {
                val colGap = UIScale.scale(12)
                val colWidth = (contentWidth - colGap) / 2
                val rtl = !parent.componentOrientation.isLeftToRight
                val firstX = if (rtl) insets.left + colWidth + colGap else insets.left
                val secondX = if (rtl) insets.left else insets.left + colWidth + colGap
                var firstY = y
                var secondY = y
                visibles.forEachIndexed { index, view ->
                    view.setSize(colWidth, Int.MAX_VALUE)
                    val height = view.preferredSize.height
                    if (index % 2 == 0) {
                        view.setBounds(firstX, firstY, colWidth, height)
                        firstY += height + gap
                    } else {
                        view.setBounds(secondX, secondY, colWidth, height)
                        secondY += height + gap
                    }
                }
                y = maxOf(firstY, secondY)
            }

            if (noProvidersPanel.isVisible) {
                noProvidersPanel.setSize(contentWidth, Int.MAX_VALUE)
                val height = noProvidersPanel.preferredSize.height
                noProvidersPanel.setBounds(insets.left, y, contentWidth, height)
                y += height + gap
            }
        }

        private fun isSingleColumn(): Boolean = quickMode

        private fun measure(parent: Container, width: Int): Dimension {
            val insets = parent.insets
            val contentWidth = (width - insets.left - insets.right).coerceAtLeast(0)
            val gap = UIScale.scale(if (quickMode) 4 else 8)
            var height = insets.top + insets.bottom
            if (contentWidth <= 0) return Dimension(width, height)

            primaryView.setSize(contentWidth, Int.MAX_VALUE)
            height += primaryView.preferredSize.height + gap

            val visibles = parent.components.filter { it.isVisible && it !== primaryView && it !== noProvidersPanel }
            val singleColumn = isSingleColumn()
            if (singleColumn || !wideMode || visibles.size < 2) {
                visibles.forEach { view ->
                    view.setSize(contentWidth, Int.MAX_VALUE)
                    height += view.preferredSize.height + gap
                }
            } else {
                val colGap = UIScale.scale(12)
                val colWidth = ((contentWidth - colGap) / 2).coerceAtLeast(0)
                var first = 0
                var second = 0
                visibles.forEachIndexed { index, view ->
                    view.setSize(colWidth, Int.MAX_VALUE)
                    val viewHeight = view.preferredSize.height + gap
                    if (index % 2 == 0) first += viewHeight else second += viewHeight
                }
                height += maxOf(first, second)
            }

            if (noProvidersPanel.isVisible) {
                noProvidersPanel.setSize(contentWidth, Int.MAX_VALUE)
                height += noProvidersPanel.preferredSize.height + gap
            }
            return Dimension(width, height)
        }
    }

    /** QUICK presentation always stacks a single column. Set from the primary state on render. */
    private var quickMode = false
}
