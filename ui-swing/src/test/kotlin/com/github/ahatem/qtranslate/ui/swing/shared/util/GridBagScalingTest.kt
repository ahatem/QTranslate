package com.github.ahatem.qtranslate.ui.swing.shared.util

import com.formdev.flatlaf.util.UIScale
import java.awt.GridBagLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The row/column gap GridBag inserts between components is expressed in logical pixels, the same
 * as every other spacing constant in the settings pages built on it -- it must scale with the
 * display the same way they do, not stay a fixed device-pixel count while everything around it
 * grows at 125%/150%/200%.
 */
class GridBagScalingTest {

    @AfterTest
    fun tearDown() {
        UIScale.setZoomFactor(1f)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
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
    fun `the horizontal gap between two components scales with the display`() {
        assertEquals(8, horizontalGapAt(1f))
        assertEquals(16, horizontalGapAt(2f))
    }

    @Test
    fun `the vertical gap between two rows scales with the display`() {
        assertEquals(4, verticalGapAt(1f))
        assertEquals(8, verticalGapAt(2f))
    }

    @Test
    fun `the first component in a row or column carries no leading gap at any scale`() {
        listOf(1f, 1.25f, 1.5f, 2f).forEach { zoom ->
            val panel = onEdt {
                UIScale.setZoomFactor(zoom)
                val p = JPanel()
                GridBag(p, horizontalGap = 8, verticalGap = 4).add(JLabel("a"))
                p
            }
            val layout = panel.layout as GridBagLayout
            val insets = layout.getConstraints(panel.getComponent(0)).insets
            assertEquals(0, insets.top, "zoom $zoom")
            assertEquals(0, insets.left, "zoom $zoom")
        }
    }
}
