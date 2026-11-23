package com.github.ahatem.qtranslate.ui.swing.snippingtool

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JMenuItem
import javax.swing.SwingUtilities
import kotlin.test.*

class CaptureToolbarFitTest {
    private val selection = Rectangle(30, 30, 100, 40)
    private val origin = Point(-100, -50)

    private fun capturePanel(actions: MutableList<CaptureToolbarAction>) = ScreenCapturePanel(
        { actions += CaptureToolbarAction.TRANSLATE }, { actions += CaptureToolbarAction.COPY_TEXT },
        { actions += CaptureToolbarAction.COPY_IMAGE }, { actions += CaptureToolbarAction.SAVE_IMAGE },
        { actions += CaptureToolbarAction.RECROP }, { actions += CaptureToolbarAction.CANCEL },
        buttonLabels = ScreenCapturePanel.ButtonLabels(translate = "A deliberately very long localized translation label"),
    ).also { it.size = Dimension(1000, 600) }

    private val narrowDisplays = listOf(Rectangle(-100, -50, 160, 600), Rectangle(300, -50, 160, 600))

    @Test
    fun `no display can fit full row so compact toolbar retains every action`() {
        SwingUtilities.invokeAndWait {
            val actions = mutableListOf<CaptureToolbarAction>()
            val panel = capturePanel(actions)
            val location = panel.calculateButtonsPosition(selection, narrowDisplays, origin)
            assertTrue(panel.isToolbarCompact())
            val bounds = Rectangle(location, panel.toolbarBounds().size).apply { translate(origin.x, origin.y) }
            assertTrue(narrowDisplays.any { it.contains(bounds) })
            val overflow = panel.overflowButtonBounds()
            assertTrue(Rectangle(Point(), panel.toolbarBounds().size).contains(overflow))
            assertTrue(overflow.width > 0 && overflow.height > 0, "overflow must stay reachable")
            panel.render(ScreenCaptureState(BufferedImage(1000, 600, BufferedImage.TYPE_INT_RGB), selection = selection))
            panel.overflowMenu().components.filterIsInstance<JMenuItem>().forEach { it.doClick(0) }
            assertEquals(CaptureToolbarAction.entries.toSet(), actions.toSet())

            // Too short for any toolbar: overflow is bounded, never placed using a row taller than every display.
            val tiny = listOf(Rectangle(-100, -50, 30, 18))
            val tinyPosition = panel.calculateButtonsPosition(selection, tiny, origin)
            assertTrue(tiny.single().contains(Rectangle(tinyPosition.x + origin.x, tinyPosition.y + origin.y,
                panel.toolbarBounds().width, panel.toolbarBounds().height)))
            assertTrue(panel.overflowButtonBounds().width > 0)
        }
    }

    @Test
    fun `a fitting display restores the full toolbar`() {
        SwingUtilities.invokeAndWait {
            val panel = capturePanel(mutableListOf())
            panel.calculateButtonsPosition(selection, narrowDisplays, origin)
            assertTrue(panel.isToolbarCompact())

            panel.calculateButtonsPosition(selection, listOf(Rectangle(-100, -50, 3000, 1000)), origin)
            assertFalse(panel.isToolbarCompact(), "a fitting display restores the full toolbar")
        }
    }
}
