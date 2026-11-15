package com.github.ahatem.qtranslate.ui.swing.ocr

import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The OCR workspace's floating zoom bar used to decide which of its controls were visible
 * from inside the parent's layout pass. Setting `isVisible` invalidates the child, so the
 * layout re-entered itself and the bar repainted in a loop until the pointer moved over the
 * window to force it to settle. Layout must only position.
 */
class OverlayPlacementTest {

    private class Probe : JPanel() {
        val visibilityChanges = mutableListOf<String>()

        override fun doLayout() {
            super.doLayout()
            for (child in components) {
                visibilityChanges += "${child.name}:${child.isVisible}"
            }
        }
    }

    private fun component(name: String, size: Dimension) =
        JPanel().apply {
            this.name = name
            preferredSize = size
        }

    /** The floating bar, marked the way the workspace marks its zoom controls. */
    private fun overlay(name: String, size: Dimension) =
        component(name, size).apply { putClientProperty(OverlayPlacement.OVERLAY_KEY, true) }

    @Test
    fun `layout positions without touching visibility`() {
        SwingUtilities.invokeAndWait {
            val content = component("content", Dimension(100, 100))
            val bar = overlay("bar", Dimension(80, 20))
            val parent = Probe().apply {
                layout = OverlayPlacement()
                add(content)
                add(bar)
                setSize(400, 300)
            }

            parent.doLayout()

            assertEquals(Rectangle(0, 0, 400, 300), content.bounds, "the content fills the panel")
            assertEquals(Rectangle(160, 280, 80, 20), bar.bounds, "the bar is centred along the bottom")
            // Every recorded state is the one the test set; layout changed nothing. The list is in
            // paint order because the bar was raised to index 0.
            assertEquals(listOf("bar:true", "content:true"), parent.visibilityChanges)
        }
    }

    @Test
    fun `a hidden bar keeps its bounds untouched`() {
        SwingUtilities.invokeAndWait {
            val content = component("content", Dimension(100, 100))
            val bar = overlay("bar", Dimension(80, 20)).apply { isVisible = false }
            val parent = Probe().apply {
                layout = OverlayPlacement()
                add(content)
                add(bar)
                setSize(400, 300)
            }

            parent.doLayout()

            assertEquals(Rectangle(0, 0, 400, 300), content.bounds)
            assertEquals(Rectangle(0, 0, 0, 0), bar.bounds, "a hidden bar is not positioned")
            assertFalse(bar.isVisible, "layout leaves the bar's visibility alone")
        }
    }

    @Test
    fun `the bar is painted above a full-size sibling`() {
        // Swing paints siblings in ascending child order, so a full-size panel added after the
        // bar hides it. The bar was laid out correctly and invisible for exactly that reason.
        lateinit var parent: JPanel
        SwingUtilities.invokeAndWait {
            val content = component("content", Dimension(100, 100))
            val bar = overlay("bar", Dimension(80, 20))
            parent = JPanel(OverlayPlacement()).apply {
                // Deliberately the wrong order: the bar first, the panel that covers it second.
                add(bar)
                add(content)
                setSize(400, 300)
                doLayout()
            }
        }
        // The reorder happens during the layout pass above; read the settled state here.
        SwingUtilities.invokeAndWait { }

        // Index 0 is the top of Swing's paint stack.
        assertEquals(0, parent.getComponentZOrder(parent.getComponent(0)), "the bar paints on top")
        assertEquals("bar", parent.getComponent(0).name, "the top child is the bar")
        assertEquals("content", parent.getComponent(1).name, "the full-size panel paints below")
        assertEquals(Rectangle(0, 0, 400, 300), parent.getComponent(1).bounds, "it still fills")
        assertEquals(Rectangle(160, 280, 80, 20), parent.getComponent(0).bounds, "the bar still floats")
    }

    /**
     * The dialog marks the bar instead of relying on add order, because the order that paints
     * correctly (bar first, cards second) is the opposite of the order the old layout expected.
     * Getting that wrong laid the bar out full-size and left the image a strip at the bottom.
     */
    @Test
    fun `a marked overlay is the floating child whatever the add order`() {
        SwingUtilities.invokeAndWait {
            val bar = overlay("bar", Dimension(80, 20)).apply {
                putClientProperty(OverlayPlacement.OVERLAY_KEY, true)
            }
            val cards = component("cards", Dimension(100, 100))
            val parent = JPanel(OverlayPlacement()).apply {
                add(bar)
                add(cards)
                setSize(400, 300)
            }

            parent.doLayout()

            assertEquals(Rectangle(0, 0, 400, 300), cards.bounds, "the unmarked child fills")
            assertEquals(Rectangle(160, 280, 80, 20), bar.bounds, "the marked child floats")
        }
    }

    @Test
    fun `an unmarked container is left alone`() {
        // There is no reliable way to tell content from overlay without the mark, and guessing by
        // position laid the bar out full-size. Refusing to position is the safe outcome.
        SwingUtilities.invokeAndWait {
            val content = component("content", Dimension(100, 100))
            val bar = component("bar", Dimension(80, 20))
            val parent = JPanel(OverlayPlacement()).apply {
                add(content)
                add(bar)
                setSize(400, 300)
            }

            parent.doLayout()

            assertEquals(Rectangle(0, 0, 0, 0), content.bounds)
            assertEquals(Rectangle(0, 0, 0, 0), bar.bounds)
        }
    }

    @Test
    fun `laying out repeatedly is stable`() {
        // The defect was a layout that could not settle. Two identical passes must agree.
        SwingUtilities.invokeAndWait {
            val content = component("content", Dimension(100, 100))
            val bar = overlay("bar", Dimension(80, 20))
            val parent = JPanel(OverlayPlacement()).apply {
                add(content)
                add(bar)
                setSize(401, 301)
            }

            parent.doLayout()
            val first = bar.bounds
            parent.doLayout()

            assertEquals(first, bar.bounds)
        }
    }

    @Test
    fun `the bar re-centres when a control appears or goes`() {
        // The bar is measured after the visibility flip, so a control appearing cannot leave it
        // off-centre — the original bug, where the bar kept the width it had before the flip.
        SwingUtilities.invokeAndWait {
            val content = component("content", Dimension(100, 100))
            val bar = overlay("bar", Dimension(80, 20))
            val parent = JPanel(OverlayPlacement()).apply {
                add(content)
                add(bar)
                setSize(400, 300)
            }

            parent.doLayout()
            val narrow = bar.bounds.x

            bar.preferredSize = Dimension(120, 20)
            parent.doLayout()

            assertEquals(120, bar.width, "the bar takes the width of the controls it shows")
            assertEquals((400 - 120) / 2, bar.x, "centred on the controls actually shown")
            assertFalse(narrow == bar.x, "the bar moved with its content")
        }
    }
}
