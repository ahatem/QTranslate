package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Side By Side sets Input and Output beside each other, and stacks them when the width runs out. */
class SideBySideResponsiveTest {

    private val wide get() = UISpacing.SIDE_BY_SIDE_BREAKPOINT + 400
    private val narrow get() = UISpacing.SIDE_BY_SIDE_BREAKPOINT - 100
    private val height = 500

    private fun pair(isRtl: Boolean = false): Triple<ResponsivePairSplit, JPanel, JPanel> {
        val input = JPanel()
        val output = JPanel()
        return Triple(ResponsivePairSplit(input, output, isRtl), input, output)
    }

    /** On the event thread, where the pair's own resize listener also runs. */
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun ResponsivePairSplit.resizeTo(width: Int) = edt {
        split.setSize(width, height)
        fit(width)
        split.doLayout()
    }

    private fun ResponsivePairSplit.dragTo(proportion: Double) = edt {
        split.applyLeadingProportion(proportion)
        split.doLayout()
    }

    private fun flushEvents() {
        SwingUtilities.invokeAndWait { }
        SwingUtilities.invokeAndWait { }
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    private class Leaves {
        val all = List(8) { JPanel() }
        val input get() = all[1]
        val output get() = all[3]
        val extra get() = all[5]
        val registry
            get() = ComponentRegistry(
                historyBar = all[0], inputPanel = all[1], languageBar = all[2],
                outputPanel = all[3], compareBoard = all[4], extraOutputPanel = all[5],
                translatorSelector = all[6], statusBar = all[7]
            )
    }

    @Test
    fun `the arrangement follows the width, side by side up to and including the breakpoint`() {
        val (pair, input, output) = pair()
        val breakpoint = UISpacing.SIDE_BY_SIDE_BREAKPOINT
        // width -> side by side?  Widening again restores the horizontal arrangement.
        listOf(wide to true, narrow to false, wide to true, breakpoint to true, breakpoint - 1 to false)
            .forEach { (width, sideBySide) ->
                pair.resizeTo(width)

                assertEquals(!sideBySide, pair.isStacked, "width $width")
                if (sideBySide) {
                    assertEquals(JSplitPane.HORIZONTAL_SPLIT, pair.split.orientation, "width $width")
                    assertSame(input, pair.split.leftComponent, "width $width")
                    assertSame(output, pair.split.rightComponent, "width $width")
                } else {
                    assertEquals(JSplitPane.VERTICAL_SPLIT, pair.split.orientation, "width $width")
                    assertSame(input, pair.split.topComponent, "width $width")
                    assertSame(output, pair.split.bottomComponent, "width $width")
                }
            }
    }

    @Test
    fun `crossing the breakpoint keeps the very same Input and Output`() {
        val (pair, input, output) = pair()
        pair.resizeTo(wide)
        pair.resizeTo(narrow)

        assertSame(input, pair.split.topComponent)
        assertSame(output, pair.split.bottomComponent)
        assertSame(pair.split, input.parent)
        assertSame(pair.split, output.parent)
        assertEquals(2, descendants(pair.split).count { it === input || it === output })
    }

    @Test
    fun `resizing within one arrangement never reparents anything`() {
        val (pair, _, _) = pair()
        pair.resizeTo(wide)
        var containerChanges = 0
        var orientationChanges = 0
        pair.split.addContainerListener(object : ContainerAdapter() {
            override fun componentAdded(e: ContainerEvent) { containerChanges++ }
            override fun componentRemoved(e: ContainerEvent) { containerChanges++ }
        })
        pair.split.addPropertyChangeListener(JSplitPane.ORIENTATION_PROPERTY) { orientationChanges++ }

        listOf(wide + 50, wide - 50, wide, UISpacing.SIDE_BY_SIDE_BREAKPOINT).forEach { pair.resizeTo(it) }
        assertEquals(0, containerChanges, "wide: no component may be added or removed")
        assertEquals(0, orientationChanges, "wide: the orientation may only change on crossing")

        pair.resizeTo(narrow)
        containerChanges = 0
        orientationChanges = 0
        listOf(narrow - 30, narrow + 30, narrow, 200).forEach { pair.resizeTo(it) }
        assertEquals(0, containerChanges, "stacked: no component may be added or removed")
        assertEquals(0, orientationChanges, "stacked: the orientation may only change on crossing")
    }

    @Test
    fun `a pair with no size yet is left alone`() {
        val (pair, _, _) = pair()
        pair.fit(0)
        assertFalse(pair.isStacked)
        assertEquals(JSplitPane.HORIZONTAL_SPLIT, pair.split.orientation)
    }

    @Test
    fun `a stacked right to left pair still has Input above Output`() {
        val (pair, input, output) = pair(isRtl = true)
        pair.resizeTo(narrow)
        assertSame(input, pair.split.topComponent)
        assertSame(output, pair.split.bottomComponent)
        assertTrue(input.y < output.y, "Input is drawn above Output, not just declared there")

        pair.resizeTo(wide)
        assertTrue(output.x < input.x, "wide right to left draws Output on the left")
        assertSame(output, pair.split.leftComponent)
        assertSame(input, pair.split.rightComponent)

        pair.resizeTo(narrow)
        assertSame(input, pair.split.topComponent)
        assertSame(output, pair.split.bottomComponent)
    }

    @Test
    fun `each arrangement keeps the divider where it was left`() {
        val (pair, _, _) = pair()
        pair.resizeTo(wide)
        pair.dragTo(0.3)

        pair.resizeTo(narrow)
        assertEquals(0.5, assertNotNull(pair.split.leadingProportion), 0.03, "a first stacked pair starts balanced")
        pair.dragTo(0.7)

        pair.resizeTo(wide)
        assertEquals(0.3, assertNotNull(pair.split.leadingProportion), 0.03)
        pair.resizeTo(narrow)
        assertEquals(0.7, assertNotNull(pair.split.leadingProportion), 0.03)
    }

    @Test
    fun `the arranged layout reacts to its own width and keeps Extra Output below the pair`() {
        val leaves = Leaves()
        val refs = SlotLayout.arrange(leaves.registry, WindowLayout(panes = PaneLayout.SIDE_BY_SIDE), isRtl = false).componentRefs
            as LayoutComponentRefs.WithSplitPanes
        val pair = refs.mainSplit

        fun resize(width: Int) {
            SwingUtilities.invokeAndWait { pair.setSize(width, height) }
            flushEvents()
        }

        resize(wide)
        assertEquals(JSplitPane.HORIZONTAL_SPLIT, pair.orientation)
        assertSame(leaves.input, pair.leftComponent)

        resize(narrow)
        assertEquals(JSplitPane.VERTICAL_SPLIT, pair.orientation)
        assertSame(leaves.input, pair.topComponent)
        assertTrue(SwingUtilities.isDescendingFrom(leaves.output, pair), "Output is still inside the pair")

        // The results area is the pair under the row that sits above both columns.
        val results = refs.extraSplit.topComponent as Container
        assertSame(pair, (results.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER))
        assertSame(leaves.extra, refs.extraSplit.bottomComponent)
        assertFalse(SwingUtilities.isDescendingFrom(leaves.extra, pair), "Extra Output stays outside the pair")

        SwingUtilities.invokeAndWait { refs.updateExtraOutputVisibility(false, leaves.extra) }
        flushEvents()
        assertFalse(leaves.extra.isVisible)
        assertEquals(0, refs.extraSplit.dividerSize, "a hidden Extra Output leaves no divider region")

        resize(wide)
        assertEquals(JSplitPane.HORIZONTAL_SPLIT, pair.orientation)
        assertEquals(0, refs.extraSplit.dividerSize)
    }

    @Test
    fun `a right to left layout mirrors when wide and stacks Input first when narrow`() {
        val leaves = Leaves()
        val pair = (SlotLayout.arrange(leaves.registry, WindowLayout(panes = PaneLayout.SIDE_BY_SIDE), isRtl = true).componentRefs
            as LayoutComponentRefs.WithSplitPanes).mainSplit

        SwingUtilities.invokeAndWait { pair.setSize(wide, height) }
        flushEvents()
        assertTrue(SwingUtilities.isDescendingFrom(leaves.output, pair.leftComponent as Container))
        assertSame(leaves.input, pair.rightComponent)

        SwingUtilities.invokeAndWait { pair.setSize(narrow, height) }
        flushEvents()
        assertSame(leaves.input, pair.topComponent)
        assertTrue(SwingUtilities.isDescendingFrom(leaves.output, pair.bottomComponent as Container))
    }
}
