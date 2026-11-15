package com.github.ahatem.qtranslate.ui.swing.ocr

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager

/**
 * Stacks a full-size content component under a smaller overlay pinned to the bottom centre.
 *
 * Only the bounds are assigned. A layout manager must not change which components are visible:
 * setting `isVisible` invalidates the child, and doing it from inside the parent's layout pass
 * can re-enter that pass. That is what the OCR workspace's zoom bar used to do, and the bar
 * repainted in a loop until an unrelated event forced the window to settle.
 *
 * Children are taken in the order they were added: the first fills the container, the second
 * is centred along the bottom edge.
 */
internal class OverlayPlacement : LayoutManager {

    override fun addLayoutComponent(name: String?, comp: Component) = Unit
    override fun removeLayoutComponent(comp: Component) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension =
        parent.components.firstOrNull()?.preferredSize ?: Dimension(0, 0)

    override fun minimumLayoutSize(parent: Container): Dimension =
        parent.components.firstOrNull()?.minimumSize ?: Dimension(0, 0)

    override fun layoutContainer(parent: Container) {
        val children = parent.components
        val content = children.getOrNull(0) ?: return
        content.setBounds(0, 0, parent.width, parent.height)

        val overlay = children.getOrNull(1) ?: return
        if (!overlay.isVisible) return
        val size = overlay.preferredSize
        overlay.setBounds(
            (parent.width - size.width) / 2,
            parent.height - size.height,
            size.width,
            size.height,
        )
    }
}
