package com.github.ahatem.qtranslate.ui.swing.main.layout

import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LayoutManagerOwnershipTest {
    @Test
    fun `switching layouts keeps every leaf attached exactly once`() {
        val container = JPanel(BorderLayout())
        val leaves = List(8) { JPanel() }
        val registry = ComponentRegistry(
            historyBar = leaves[0], inputPanel = leaves[1], languageBar = leaves[2],
            outputPanel = leaves[3], comparisonResultsPanel = leaves[4], extraOutputPanel = leaves[5],
            translatorSelector = leaves[6], statusBar = leaves[7]
        )
        val manager = LayoutManager(registry, container)

        listOf("comparison", "classic", "side_by_side", "comparison").forEach { layoutId ->
            switchAndFlush(manager, layoutId)
            leaves.filterIndexed { index, _ -> index != 4 && !(layoutId == "comparison" && index == 6) }.forEach { leaf ->
                assertNotNull(leaf.parent, "$layoutId orphaned a leaf")
                assertEquals(1, count(container, leaf), "$layoutId mounted a leaf more than once")
            }
            val comparisonCount = count(container, leaves[4])
            if (layoutId == "comparison") {
                assertEquals(1, comparisonCount, "comparison layout must mount its comparison leaf")
                assertEquals(0, count(container, leaves[6]), "comparison layout must keep the enhanced selector out of the footer")
                assertTrue(leaves[6].parent == null, "comparison layout must not retain a stale selector parent")
            } else {
                assertEquals(0, comparisonCount, "$layoutId must leave the comparison leaf unmounted")
                assertTrue(leaves[4].parent == null, "$layoutId must not retain a stale comparison parent")
            }
        }
    }

    @Test
    fun `comparison can be the first saved layout`() {
        val container = JPanel(BorderLayout())
        val leaves = List(8) { JPanel() }
        val registry = ComponentRegistry(
            leaves[0], leaves[1], leaves[2], leaves[3], leaves[4], leaves[5], leaves[6], leaves[7]
        )
        val manager = LayoutManager(registry, container)
        switchAndFlush(manager, "comparison")
        assertNotNull(leaves[3].parent)
        assertNotNull(leaves[4].parent)
        assertEquals(1, count(container, leaves[3]))
        assertEquals(1, count(container, leaves[4]))
    }

    private fun switchAndFlush(manager: LayoutManager, layoutId: String) {
        SwingUtilities.invokeAndWait { manager.switchLayout(layoutId) }
        SwingUtilities.invokeAndWait { }
    }

    private fun count(root: java.awt.Container, target: java.awt.Component): Int =
        root.components.sumOf { child ->
            (if (child === target) 1 else 0) +
                if (child is java.awt.Container) count(child, target) else 0
        }
}
