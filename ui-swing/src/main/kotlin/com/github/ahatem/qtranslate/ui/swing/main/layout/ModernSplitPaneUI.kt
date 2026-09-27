package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.ui.FlatSplitPaneUI
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JSplitPane
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

/**
 * Gives a [JSplitPane] the [WorkspaceGripDivider] every internal workspace split is resized by,
 * instead of FlatLaf's own divider and its gutter-coloured grip.
 *
 * The gutter itself paints nothing; only the grip marks at its centre do, and they take their hover
 * colour while the pointer is anywhere over the gutter, not only over the marks, because the whole
 * gutter is the drag target. One-touch expand arrows are switched off unconditionally: QTranslate
 * already owns whether a region is visible, and the arrows are dated, unused chrome on top of that.
 */
class ModernSplitPaneUI : FlatSplitPaneUI() {

    override fun createDefaultDivider(): BasicSplitPaneDivider = GripDivider(this)

    override fun installDefaults() {
        super.installDefaults()
        splitPane.isOneTouchExpandable = false
    }

    private class GripDivider(ui: BasicSplitPaneUI) : BasicSplitPaneDivider(ui) {
        private var hovered = false
        private var dragging = false

        init {
            border = null
            val mouse = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
                override fun mousePressed(e: MouseEvent) { dragging = true; repaint() }
                override fun mouseReleased(e: MouseEvent) { dragging = false; repaint() }
            }
            addMouseListener(mouse)
        }

        override fun paint(g: Graphics) {
            val state = when {
                dragging -> WorkspaceGripDivider.State.DRAG
                hovered -> WorkspaceGripDivider.State.HOVER
                else -> WorkspaceGripDivider.State.REST
            }
            WorkspaceGripDivider.paint(g, width, height, vertical = orientation == JSplitPane.HORIZONTAL_SPLIT, state = state)
        }
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun createUI(component: javax.swing.JComponent) = ModernSplitPaneUI()
    }
}
