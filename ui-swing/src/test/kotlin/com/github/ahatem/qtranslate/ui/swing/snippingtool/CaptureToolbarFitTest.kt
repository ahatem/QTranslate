package com.github.ahatem.qtranslate.ui.swing.snippingtool

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JMenuItem
import javax.swing.SwingUtilities
import kotlin.test.*

class CaptureToolbarFitTest {
    @Test
    fun `no display can fit full row so compact toolbar retains every action`() {
        SwingUtilities.invokeAndWait {
            val actions = mutableListOf<CaptureToolbarAction>()
            val panel = ScreenCapturePanel(
                { actions += CaptureToolbarAction.TRANSLATE }, { actions += CaptureToolbarAction.COPY_TEXT },
                { actions += CaptureToolbarAction.COPY_IMAGE }, { actions += CaptureToolbarAction.SAVE_IMAGE },
                { actions += CaptureToolbarAction.RECROP }, { actions += CaptureToolbarAction.CANCEL },
                buttonLabels = ScreenCapturePanel.ButtonLabels(translate = "A deliberately very long localized translation label"),
            )
            panel.size = Dimension(1000, 600)
            val selection = Rectangle(30, 30, 100, 40)
            val displays = listOf(Rectangle(-100, -50, 160, 600), Rectangle(300, -50, 160, 600))
            val origin = Point(-100, -50)
            val location = panel.calculateButtonsPosition(selection, displays, origin)
            assertTrue(panel.isToolbarCompact())
            val bounds = Rectangle(location, panel.toolbarBounds().size).apply { translate(origin.x, origin.y) }
            assertTrue(displays.any { it.contains(bounds) })
            val overflow = panel.overflowButtonBounds()
            assertTrue(Rectangle(Point(), panel.toolbarBounds().size).contains(overflow))
            assertTrue(overflow.width > 0 && overflow.height > 0, "overflow must stay reachable")
            panel.render(ScreenCaptureState(BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB), selection = selection))
            panel.overflowMenu().components.filterIsInstance<JMenuItem>().forEach { it.doClick(0) }
            assertEquals(CaptureToolbarAction.entries.toSet(), actions.toSet())

            // Even insufficient vertical space has deliberate bounded overflow, not impossible
            // placement using a full-row height that is taller than every display.
            val tiny = listOf(Rectangle(-100, -50, 30, 18))
            val tinyPosition = panel.calculateButtonsPosition(selection, tiny, origin)
            assertTrue(tiny.single().contains(Rectangle(tinyPosition.x + origin.x, tinyPosition.y + origin.y,
                panel.toolbarBounds().width, panel.toolbarBounds().height)))
            assertTrue(panel.overflowButtonBounds().width > 0)

            panel.calculateButtonsPosition(selection, listOf(Rectangle(-100, -50, 3000, 1000)), origin)
            assertFalse(panel.isToolbarCompact(), "a fitting display restores the full toolbar")
        }
    }
}
