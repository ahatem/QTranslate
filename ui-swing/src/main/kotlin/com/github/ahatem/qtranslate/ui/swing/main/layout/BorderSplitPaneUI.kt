package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.ui.FlatSplitPaneUI
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JSplitPane
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

/**
 * Gives a [JSplitPane] the [BoundaryDivider] hairline every region boundary has,
 * instead of [WorkspaceGripDivider]'s grip dots.
 *
 * Internal workspace splits separate two panes of the same region and read as a
 * grip; a region boundary reads as a border. The OCR Workspace pairs an image
 * viewer with text, so its seam follows the dictionary dock's border, not the
 * input/output grip. The grip stays for the workspace's own splits.
 */
class BorderSplitPaneUI : FlatSplitPaneUI() {

    override fun createDefaultDivider(): BasicSplitPaneDivider = BorderDivider(this)

    override fun installDefaults() {
        super.installDefaults()
        splitPane.isOneTouchExpandable = false
    }

    private class BorderDivider(ui: BasicSplitPaneUI) : BasicSplitPaneDivider(ui) {
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
            BoundaryDivider.paint(g, width, height, vertical = orientation == JSplitPane.HORIZONTAL_SPLIT, active = hovered || dragging)
        }
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun createUI(component: javax.swing.JComponent) = BorderSplitPaneUI()
    }
}
