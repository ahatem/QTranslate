package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.GridBagLayout
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Gaps are logical pixels, so they scale with the display like every other spacing constant. */
class GridBagScalingTest {

    @AfterTest
    fun tearDown() {
        UIScale.setZoomFactor(1f)
    }

    private fun horizontalGapAt(zoom: Float): Int = onEdt {
        UIScale.setZoomFactor(zoom)
        val panel = JPanel()
        val gb = GridBag(panel, horizontalGap = 8, verticalGap = 4)
        gb.add(JLabel("a"))
        gb.add(JLabel("b"))
        val layout = panel.layout as GridBagLayout
        layout.getConstraints(panel.getComponent(1)).insets.left
    }

    private fun verticalGapAt(zoom: Float): Int = onEdt {
        UIScale.setZoomFactor(zoom)
        val panel = JPanel()
        val gb = GridBag(panel, horizontalGap = 8, verticalGap = 4)
        gb.add(JLabel("a"))
        gb.nextRow()
        gb.add(JLabel("b"))
        val layout = panel.layout as GridBagLayout
        layout.getConstraints(panel.getComponent(1)).insets.top
    }

    @Test
    fun `gaps between components and rows scale with the display`() {
        for ((zoom, horizontal, vertical) in listOf(Triple(1f, 8, 4), Triple(2f, 16, 8))) {
            assertEquals(horizontal, horizontalGapAt(zoom), "horizontal gap at zoom $zoom")
            assertEquals(vertical, verticalGapAt(zoom), "vertical gap at zoom $zoom")
        }
    }
}
