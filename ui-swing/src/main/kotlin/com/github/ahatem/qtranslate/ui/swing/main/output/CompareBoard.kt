package com.github.ahatem.qtranslate.ui.swing.main.output

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.main.selector.TranslatorPopupButton
import com.github.ahatem.qtranslate.ui.swing.shared.icon.IconManager
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.Scrollable
import javax.swing.SwingConstants

data class CompareBoardState(
    val primary: TranslationProviderState,
    val secondaries: List<TranslationProviderState>
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

    private val boardLayout = CompareBoardLayout()
    private var lastOuterPad = -1

    /**
     * Board-owned section rules: plain FlatLaf [JSeparator]s, no cards, no
     * custom painting. The primary boundary spans the board once comparison
     * results follow; inter-provider rules sit between secondary sections;
     * the center divider spans the gutter only in two-column mode.
     */
    private val primaryRule = JSeparator(SwingConstants.HORIZONTAL).apply { isVisible = false }
    private val centerRule = JSeparator(SwingConstants.VERTICAL).apply { isVisible = false }
    private val interRules = mutableListOf<JSeparator>()

    val primaryProviderView: TranslationProviderView get() = primaryView

    init {
        isOpaque = false
        layout = boardLayout
        add(primaryView)
        add(primaryRule)
        add(centerRule)
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
        syncInterRules(state.secondaries.size)
        if (primaryView.parent === this) {
            remove(primaryView)
            add(primaryView, 0)
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

    /** Pooled inter-provider rules; the layout shows only the needed prefix. */
    private fun syncInterRules(secondaryCount: Int) {
        val want = (secondaryCount - 1).coerceAtLeast(0)
        while (interRules.size < want) {
            JSeparator(SwingConstants.HORIZONTAL).apply { isVisible = false }
                .also { interRules.add(it); add(it) }
        }
    }

    /** Test accessors: structural separation without pixel assertions. */
    fun primaryBoundaryForTest(): JSeparator = primaryRule
    fun centerDividerForTest(): JSeparator = centerRule
    fun sectionRulesForTest(): List<JSeparator> = interRules.toList()

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
            val rulePad = UIScale.scale(if (quickMode) 2 else 4)
            val ruleThickness = primaryRule.preferredSize.height.coerceAtLeast(1)

            primaryView.setSize(contentWidth, Int.MAX_VALUE)
            val primaryHeight = primaryView.preferredSize.height
            primaryView.setBounds(insets.left, y, contentWidth, primaryHeight)
            y += primaryHeight

            val secondaries = parent.components
                .filter { it.isVisible && it is TranslationProviderView && it !== primaryView }

            // ONE primary-to-secondary boundary when comparison results follow.
            // A lone primary and the empty state keep their approved spacing
            // with no dividers.
            primaryRule.isVisible = secondaries.isNotEmpty()
            if (primaryRule.isVisible) {
                y += rulePad
                primaryRule.setBounds(insets.left, y, contentWidth, ruleThickness)
                y += ruleThickness + rulePad
            } else {
                y += gap
            }

            centerRule.isVisible = false
            var usedRules = 0
            if (singleColumn || !wideMode || secondaries.size < 2) {
                secondaries.forEachIndexed { index, view ->
                    if (index > 0) {
                        // Between sections only: no rule above the first
                        // secondary (the primary boundary covers that) and
                        // none after the final provider.
                        interRules.getOrNull(usedRules++)?.let { rule ->
                            rule.isVisible = true
                            y += rulePad
                            rule.setBounds(insets.left, y, contentWidth, ruleThickness)
                            y += ruleThickness + rulePad
                        }
                    }
                    view.setSize(contentWidth, Int.MAX_VALUE)
                    val height = view.preferredSize.height
                    view.setBounds(insets.left, y, contentWidth, height)
                    y += height + gap
                }
            } else {
                val colGap = UIScale.scale(WIDE_COLUMN_GAP)
                val colWidth = (contentWidth - colGap) / 2
                val rtl = !parent.componentOrientation.isLeftToRight
                val firstX = if (rtl) insets.left + colWidth + colGap else insets.left
                val secondX = if (rtl) insets.left else insets.left + colWidth + colGap
                var firstY = y
                var secondY = y
                // Natural heights per lane; each lane's own rules sit at that
                // lane's positions rather than on a forced shared grid.
                val laneCounts = intArrayOf(0, 0)
                secondaries.forEachIndexed { index, view ->
                    val lane = index % 2
                    val x = if (lane == 0) firstX else secondX
                    var laneY = if (lane == 0) firstY else secondY
                    if (laneCounts[lane] > 0) {
                        interRules.getOrNull(usedRules++)?.let { rule ->
                            rule.isVisible = true
                            laneY += rulePad
                            rule.setBounds(x, laneY, colWidth, ruleThickness)
                            laneY += ruleThickness + rulePad
                        }
                    }
                    laneCounts[lane]++
                    view.setSize(colWidth, Int.MAX_VALUE)
                    val height = view.preferredSize.height
                    view.setBounds(x, laneY, colWidth, height)
                    laneY += height + gap
                    if (lane == 0) firstY = laneY else secondY = laneY
                }
                // ONE central divider for the two reading lanes: board-owned,
                // spanning the useful secondary height, with the widened
                // gutter keeping text clear of the line on both sides.
                val blockTop = y
                val blockBottom = maxOf(firstY, secondY) - gap
                if (blockBottom > blockTop) {
                    centerRule.isVisible = true
                    val dividerWidth = centerRule.preferredSize.width.coerceAtLeast(1)
                    val centerX = insets.left + colWidth + colGap / 2
                    centerRule.setBounds(
                        centerX - dividerWidth / 2, blockTop,
                        dividerWidth, blockBottom - blockTop
                    )
                }
                y = maxOf(firstY, secondY)
            }
            for (i in usedRules until interRules.size) interRules[i].isVisible = false
        }

        private fun isSingleColumn(): Boolean = quickMode

        private fun measure(parent: Container, width: Int): Dimension {
            val insets = parent.insets
            val contentWidth = (width - insets.left - insets.right).coerceAtLeast(0)
            val gap = UIScale.scale(if (quickMode) 4 else 8)
            val rulePad = UIScale.scale(if (quickMode) 2 else 4)
            val ruleThickness = primaryRule.preferredSize.height.coerceAtLeast(1)
            var height = insets.top + insets.bottom
            if (contentWidth <= 0) return Dimension(width, height)

            primaryView.setSize(contentWidth, Int.MAX_VALUE)
            height += primaryView.preferredSize.height

            val secondaries = parent.components
                .filter { it.isVisible && it is TranslationProviderView && it !== primaryView }
            height += if (secondaries.isNotEmpty()) {
                2 * rulePad + ruleThickness
            } else {
                gap
            }
            val singleColumn = isSingleColumn()
            if (singleColumn || !wideMode || secondaries.size < 2) {
                secondaries.forEach { view ->
                    view.setSize(contentWidth, Int.MAX_VALUE)
                    height += view.preferredSize.height + gap
                }
                height += (secondaries.size - 1).coerceAtLeast(0) * (2 * rulePad + ruleThickness)
            } else {
                val colGap = UIScale.scale(WIDE_COLUMN_GAP)
                val colWidth = ((contentWidth - colGap) / 2).coerceAtLeast(0)
                var first = 0
                var second = 0
                var firstCount = 0
                var secondCount = 0
                secondaries.forEachIndexed { index, view ->
                    view.setSize(colWidth, Int.MAX_VALUE)
                    val viewHeight = view.preferredSize.height + gap
                    if (index % 2 == 0) {
                        first += viewHeight
                        firstCount++
                    } else {
                        second += viewHeight
                        secondCount++
                    }
                }
                first += (firstCount - 1).coerceAtLeast(0) * (2 * rulePad + ruleThickness)
                second += (secondCount - 1).coerceAtLeast(0) * (2 * rulePad + ruleThickness)
                height += maxOf(first, second)
            }

            return Dimension(width, height)
        }
    }

    /** QUICK presentation always stacks a single column. Set from the primary state on render. */
    private var quickMode = false

    private companion object {
        /**
         * Center gutter in two-column mode: ~9-10px of breathing room on each
         * side of the 1px divider, still compact next to the 8px row rhythm.
         */
        const val WIDE_COLUMN_GAP = 20
    }
}
