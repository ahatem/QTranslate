package com.github.ahatem.qtranslate.ui.swing.main.layout

import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutManagerOwnershipTest {
    @Test
    fun `switching layouts keeps every leaf attached exactly once`() {
        val container = JPanel(BorderLayout())
        val leaves = List(8) { JPanel() }
        val registry = ComponentRegistry(
            historyBar = leaves[0], inputPanel = leaves[1], languageBar = leaves[2],
            outputPanel = leaves[3], compareBoard = leaves[4], extraOutputPanel = leaves[5],
            translatorSelector = leaves[6], statusBar = leaves[7]
        )
        val manager = LayoutManager(registry, container)

        listOf("comparison", "classic", "side_by_side", "comparison", "compact", "comparison").forEach { layoutId ->
            switchAndFlush(manager, layoutId)
            // Shared chrome is always mounted exactly once.
            listOf(leaves[0], leaves[1], leaves[2], leaves[5], leaves[7]).forEach { leaf ->
                assertNotNull(leaf.parent, "$layoutId orphaned a leaf")
                assertEquals(1, count(container, leaf), "$layoutId mounted a leaf more than once")
            }
            if (layoutId == "comparison") {
                assertEquals(1, count(container, leaves[4]), "comparison layout must mount its compare board")
                assertEquals(0, count(container, leaves[3]), "comparison layout must not mount the classic output")
                assertNull(leaves[3].parent, "comparison layout must not retain a stale output parent")
                assertEquals(0, count(container, leaves[6]), "comparison layout must keep the enhanced selector out of the footer")
                assertNull(leaves[6].parent, "comparison layout must not retain a stale selector parent")
            } else {
                assertEquals(0, count(container, leaves[4]), "$layoutId must leave the compare board unmounted")
                assertNull(leaves[4].parent, "$layoutId must not retain a stale board parent")
                assertEquals(1, count(container, leaves[3]), "$layoutId must mount the classic output")
                assertEquals(1, count(container, leaves[6]), "$layoutId must mount the footer selector")
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
        assertNotNull(leaves[1].parent)
        assertNotNull(leaves[4].parent)
        assertEquals(1, count(container, leaves[1]))
        assertEquals(1, count(container, leaves[4]))
        assertTrue(leaves[3].parent == null, "comparison must not mount the classic output")
        assertTrue(leaves[6].parent == null, "comparison must not mount the footer selector")
    }

    private fun switchAndFlush(manager: LayoutManager, layoutId: String) {
        SwingUtilities.invokeAndWait { manager.switchLayout(layoutId) }
        SwingUtilities.invokeAndWait { }
        // Compact mounts its extra tab from a runnable posted during arrangement,
        // so one more pump settles layouts that build asynchronously.
        SwingUtilities.invokeAndWait { }
    }

    private fun count(root: java.awt.Container, target: java.awt.Component): Int =
        root.components.sumOf { child ->
            (if (child === target) 1 else 0) +
                if (child is java.awt.Container) count(child, target) else 0
        }
}
