package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.ui.swing.shared.util.clearBorder
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JSplitPane

/**
 * The Input/Output pair of Side By Side: beside each other while the width allows it, stacked
 * with Input above Output when it does not.
 *
 * The pair lives in one [split] whose orientation changes, so [leading] and [trailing] are never
 * detached or rebuilt. Text, selection and scroll position survive the change, and the width
 * alone decides the arrangement: nothing here reads or writes the saved layout.
 *
 * Each arrangement remembers where its divider was last left, for the rest of the session.
 *
 * @param leading  shown first in reading order: on the left of a left-to-right interface and on
 *                 the right of a right-to-left one. [MirroredSplitPane] does the exchange, so
 *                 callers pass reading order.
 * @param isRtl    whether the interface reads right to left. Only the side-by-side arrangement
 *                 mirrors; stacked, Input stays above Output whatever the direction.
 */
class ResponsivePairSplit(
    private val leading: JComponent,
    private val trailing: JComponent,
    private val isRtl: Boolean,
) {
    val split = MirroredSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, leading, trailing).apply {
        leadingResizeWeight = 0.5
        dividerSize = UISpacing.DIVIDER_SIZE
        clearBorder()
    }

    var isStacked: Boolean = false
        private set

    private var sideBySideProportion = 0.5
    private var stackedProportion = 0.5
    private var isRearranging = false

    init {
        arrange()
        split.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = fit(split.width)
        })
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) { rememberDivider() }
    }

    /** Chooses the arrangement for a pair [width] wide; does nothing unless it crosses the breakpoint. */
    fun fit(width: Int) {
        if (width <= 0) return
        val stacked = width < UISpacing.SIDE_BY_SIDE_BREAKPOINT
        if (stacked == isStacked) return

        val restored = if (stacked) stackedProportion else sideBySideProportion
        isRearranging = true
        try {
            isStacked = stacked
            arrange()
            split.applyLeadingProportion(restored)
        } finally {
            isRearranging = false
        }
    }

    /** Follows every move of the divider, by the user or by a resize, for the arrangement it is in. */
    private fun rememberDivider() {
        if (isRearranging) return
        val proportion = split.leadingProportion ?: return
        if (isStacked) stackedProportion = proportion else sideBySideProportion = proportion
    }

    private fun arrange() {
        val minimum = if (isStacked) Dimension(0, UISpacing.MIN_PANEL_HEIGHT) else Dimension(UISpacing.MIN_PANEL_WIDTH, 0)
        leading.minimumSize = minimum
        trailing.minimumSize = minimum
        split.orientation = if (isStacked) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT
        split.isMirrored = isRtl && !isStacked
    }
}
