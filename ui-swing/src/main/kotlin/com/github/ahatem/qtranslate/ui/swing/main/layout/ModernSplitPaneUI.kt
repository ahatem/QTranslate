package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.formdev.flatlaf.ui.FlatSplitPaneUI
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JSplitPane
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

/**
 * Gives a [JSplitPane] the workspace half of [ModernSplitDivider]'s shared language, instead of
 * FlatLaf's default three-dot grip.
 *
 * Unlike [WorkspaceDockHost]'s own boundary, which rests as a hairline because it separates the
 * whole workspace from a side panel, these dividers split two panes of the same region and rest at
 * a visibly thicker line so they still read as a real, discoverable splitter — not merely a border
 * — while staying just as calm as the dock's until the pointer approaches. One-touch expand arrows
 * are switched off unconditionally: QTranslate already owns whether a region is visible, and the
 * arrows are dated, unused chrome on top of that.
 */
class ModernSplitPaneUI : FlatSplitPaneUI() {

    override fun createDefaultDivider(): BasicSplitPaneDivider = ModernDivider(this)

    override fun installDefaults() {
        super.installDefaults()
        splitPane.isOneTouchExpandable = false
    }

    private class ModernDivider(ui: BasicSplitPaneUI) : BasicSplitPaneDivider(ui) {
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
            val vertical = orientation == JSplitPane.HORIZONTAL_SPLIT
            ModernSplitDivider.paint(g, width, height, vertical, active = hovered || dragging, style = ModernSplitDivider.Style.WORKSPACE)
        }
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun createUI(component: javax.swing.JComponent) = ModernSplitPaneUI()
    }
}
