package com.github.ahatem.qtranslate.ui.swing.ocr

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JComponent

/**
 * Stacks a full-size content component under a smaller overlay pinned to the bottom centre.
 *
 * Only the bounds are assigned. A layout manager must not change which components are visible:
 * setting `isVisible` invalidates the child, and doing it from inside the parent's layout pass
 * can re-enter that pass. That is what the OCR workspace's zoom bar used to do, and the bar
 * repainted in a loop until an unrelated event forced the window to settle.
 *
 * Which child is which is decided by [OVERLAY_KEY], not by the order the children were added.
 * Paint order and stacking order genuinely differ here: the overlay has to be added *first* so it
 * sits below nothing, and only the caller knows which panel it meant to float. Reading position
 * instead of intent laid the zoom bar out full-size and gave the image a leftover strip, and a
 * positional guess would also break the moment [layoutContainer] reorders the children.
 *
 * Marked overlays are also painted last. Swing paints siblings from the highest index down, so
 * index 0 is the top of the stack: a full-size panel at a higher index would cover the overlay
 * completely, which is why the bar was once laid out correctly and invisible.
 */
internal class OverlayPlacement : LayoutManager {

    override fun addLayoutComponent(name: String?, comp: Component) = Unit
    override fun removeLayoutComponent(comp: Component) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension =
        parent.contentComponent()?.preferredSize ?: Dimension(0, 0)

    override fun minimumLayoutSize(parent: Container): Dimension =
        parent.contentComponent()?.minimumSize ?: Dimension(0, 0)

    override fun layoutContainer(parent: Container) {
        val overlay = parent.overlayComponent() ?: return
        // Everything that is not the overlay fills the container; in practice exactly one card
        // panel, but this stays correct if a background or scrim is added later.
        parent.components.filter { it !== overlay }.forEach { it.setBounds(0, 0, parent.width, parent.height) }

        // Swing paints siblings from the highest index down, so index 0 is the top of the stack.
        // Do this before positioning so the overlay is never painted under a panel mid-pass; a
        // container accepts a z-order change made inside its own layout pass.
        if (parent.components.firstOrNull() !== overlay) {
            parent.setComponentZOrder(overlay, 0)
        }

        if (!overlay.isVisible) return
        val size = overlay.preferredSize
        overlay.setBounds(
            (parent.width - size.width) / 2,
            parent.height - size.height,
            size.width,
            size.height,
        )
    }

    companion object {
        /**
         * Set to true on the child that floats above the rest. Every other child fills the
         * container. Unmarked is a wiring mistake: there is no way to tell content from overlay
         * reliably, so the layout leaves the children alone rather than guessing.
         */
        const val OVERLAY_KEY: String = "qtranslate.overlayPlacement.isOverlay"

        private fun JComponent.isOverlay(): Boolean = getClientProperty(OVERLAY_KEY) == true
    }
}

private fun Container.overlayComponent(): Component? =
    components.singleOrNull { it is JComponent && it.isOverlayMarked() }

private fun Container.contentComponent(): Component? =
    components.firstOrNull { !(it is JComponent && it.isOverlayMarked()) }

private fun Component.isOverlayMarked(): Boolean =
    (this as? JComponent)?.getClientProperty(OverlayPlacement.OVERLAY_KEY) == true
