package com.github.ahatem.qtranslate.ui.swing.main.layout

import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JSplitPane


/**
 * A [JSplitPane] that mirrors by swapping its children, not by component orientation.
 *
 * Swing lays out both split orientations with the same implementation, and that implementation
 * inverts its coordinates when the component orientation is right-to-left. Two things go wrong
 * when the orientation is applied to a whole window:
 *
 * - **Vertical splits reverse.** A vertical split has no leading or trailing side, but it inherits
 *   the inversion anyway, so the bottom component is drawn on top. With the interface in Arabic
 *   the output pane sat above the input.
 * - **Horizontal splits stop dragging correctly.** The divider's position is computed from the
 *   inverted axis while the mouse reports normal coordinates, so dragging the docked dictionary
 *   moves the wrong way or refuses to move, and [setResizeWeight] favours the wrong side when the
 *   window is resized.
 *
 * This pane keeps itself left-to-right whatever the cascade asks for, and implements a right-to-
 * left arrangement by exchanging the two children instead. Layout and dragging then run on
 * coordinates that match the mouse, while the children still receive the orientation themselves —
 * text alignment, toolbars and the title bar mirror as they should.
 *
 * @param leading  the component shown first in reading order — left in a left-to-right interface,
 *                 right in a right-to-left one.
 * @param trailing the component shown second.
 */
class MirroredSplitPane(
    orientation: Int,
    continuousLayout: Boolean,
    private val leading: Component,
    private val trailing: Component,
) : JSplitPane(orientation, continuousLayout, leading, trailing) {

    init {
        // The workspace's own grip, not FlatLaf's grip dots.
        setUI(ModernSplitPaneUI())
    }

    /** Share of the space given to [leading] when the window is resized. */
    var leadingResizeWeight: Double = 0.5
        set(value) {
            field = value
            resizeWeight = if (isMirrored) 1.0 - value else value
        }

    /**
     * Whether the children are exchanged, i.e. whether the interface reads right to left.
     *
     * Set explicitly by the owner rather than inferred from [setComponentOrientation]. The
     * orientation cascade reaches this pane at a point in startup that depends on when the
     * enclosing view was added to the window, so relying on it left the arrangement unset in some
     * orders. The owner knows the interface direction outright.
     */
    var isMirrored: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            resizeWeight = if (value) 1.0 - leadingResizeWeight else leadingResizeWeight
            rearrange()
        }

    override fun setComponentOrientation(orientation: ComponentOrientation) {
        super.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT)
    }

    /**
     * Answered here as well, because a pane built after the interface was mirrored is handed the
     * direction when it is added to its parent, without passing through the setter above. Left
     * alone, it would then lay itself out inverted, and a split created by switching layouts in a
     * right-to-left interface would come out with its sides the wrong way round.
     */
    override fun getComponentOrientation(): ComponentOrientation = ComponentOrientation.LEFT_TO_RIGHT

    /** Size along the split axis that the divider divides, or 0 while the pane has none. */
    private val splitExtent: Int
        get() = ((if (orientation == HORIZONTAL_SPLIT) width else height) - dividerSize).coerceAtLeast(0)

    /** The share of the space currently held by [leading], or null while the pane has no size. */
    val leadingProportion: Double?
        get() {
            val extent = splitExtent
            if (extent == 0) return null
            val proportion = dividerLocation.toDouble() / extent
            return if (isMirrored) 1.0 - proportion else proportion
        }

    /**
     * Gives [leading] the share [proportion] of the space along the split axis, right now.
     *
     * Callers think in reading order, so the proportion is for the leading component and is
     * converted here — passing it raw would put the panel on the wrong side of the divider in a
     * right-to-left interface.
     *
     * @return false, having done nothing, while the pane has no size to divide.
     */
    fun applyLeadingProportion(proportion: Double): Boolean {
        if (splitExtent == 0) return false
        setDividerLocation(if (isMirrored) 1.0 - proportion else proportion)
        return true
    }

    /**
     * Like [applyLeadingProportion], but holds the request until the pane can honour it.
     *
     * [setDividerLocation] with a proportion is silently ignored while the pane has no size,
     * which is the usual reason a panel opens pinned to its minimum width instead of the share it
     * asked for. If the size is not known yet the request is held until the first layout.
     */
    fun setLeadingProportion(proportion: Double) {
        if (isShowing && applyLeadingProportion(proportion)) return
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (!applyLeadingProportion(proportion)) return
                removeComponentListener(this)
            }
        })
    }

    private fun rearrange() {
        val proportion = if (splitExtent > 0) dividerLocation.toDouble() / splitExtent else -1.0

        // JSplitPane refuses a component that is still installed in the other slot, so both have
        // to be detached before either is put back. The setters are called by name: written as
        // property assignments, Kotlin resolves them to the superclass's protected fields, which
        // swaps what the pane reports without ever telling its layout.
        setLeftComponent(null)
        setRightComponent(null)
        setLeftComponent(if (isMirrored) trailing else leading)
        setRightComponent(if (isMirrored) leading else trailing)

        revalidate()
        if (proportion > 0.0 && proportion < 1.0) setDividerLocation(1.0 - proportion)
        repaint()
    }
}
