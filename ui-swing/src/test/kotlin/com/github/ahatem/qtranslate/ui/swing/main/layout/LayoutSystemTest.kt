package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The main window offers Classic, Side By Side and Comparison, and nothing else. */
class LayoutSystemTest {

    private class Leaves {
        val all = List(8) { JPanel() }
        val input get() = all[1]
        val output get() = all[3]
        val board get() = all[4]
        val extra get() = all[5]
        val registry
            get() = ComponentRegistry(
                historyBar = all[0], inputPanel = all[1], languageBar = all[2],
                outputPanel = all[3], compareBoard = all[4], extraOutputPanel = all[5],
                translatorSelector = all[6], statusBar = all[7]
            )
    }

    private val strategies = listOf(ClassicLayout, SideBySideLayout, ComparisonLayout)

    private fun flush() {
        repeat(3) { SwingUtilities.invokeAndWait { } }
    }

    private fun count(root: Container, target: Component): Int =
        root.components.sumOf { child ->
            (if (child === target) 1 else 0) + if (child is Container) count(child, target) else 0
        }

    private fun ancestors(component: Component): List<Container> =
        generateSequence(component.parent) { it.parent }.toList()

    @Test
    fun `the available layouts are exactly Classic, Side By Side and Comparison`() {
        assertEquals(
            listOf(LayoutType.CLASSIC, LayoutType.SIDE_BY_SIDE, LayoutType.COMPARISON),
            LayoutManager.getAvailableLayouts().map { it.type }
        )
        assertEquals(
            listOf("classic", "side_by_side", "comparison"),
            LayoutManager.getAvailableLayouts().map { it.id }
        )
        assertEquals(
            listOf("layout_preset_classic", "layout_preset_side_by_side", "layout_preset_comparison"),
            LayoutManager.getAvailableLayouts().map { it.localizeId }
        )
    }

    @Test
    fun `compact is not a layout and its saved id shows Classic`() {
        assertFalse(LayoutType.entries.any { it.name == "COMPACT" })
        assertFalse(LayoutManager.getAvailableLayouts().any { it.id == LayoutPresetIds.LEGACY_COMPACT })

        val manager = LayoutManager(Leaves().registry, JPanel(BorderLayout()))
        assertSame(ClassicLayout, manager.getLayoutById(LayoutPresetIds.LEGACY_COMPACT))
        assertSame(ClassicLayout, manager.getLayoutById("from_a_newer_version"))
    }

    @Test
    fun `every layout arranges its panes in split panes`() {
        strategies.forEach { strategy ->
            val refs = strategy.arrange(Leaves().registry, isRtl = false).componentRefs
            assertIs<LayoutComponentRefs.WithSplitPanes>(refs, "${strategy.id} must be built from split panes")
        }
    }

    @Test
    fun `switching between any two layouts keeps each pane attached once and the same instance`() {
        val container = JPanel(BorderLayout())
        val leaves = Leaves()
        val manager = LayoutManager(leaves.registry, container)
        val original = leaves.all.toList()

        strategies.flatMap { from -> strategies.map { to -> from to to } }.forEach { (from, to) ->
            SwingUtilities.invokeAndWait { manager.switchLayout(from.id) }
            flush()
            SwingUtilities.invokeAndWait { manager.switchLayout(to.id) }
            flush()
            listOf(leaves.all[0], leaves.input, leaves.all[2], leaves.extra, leaves.all[7]).forEach { leaf ->
                assertEquals(1, count(container, leaf), "${from.id} to ${to.id} lost or duplicated a pane")
            }
        }
        assertEquals(original, leaves.all, "the canonical panes are never replaced")
    }

    @Test
    fun `Extra Output shows and hides cleanly in every layout`() {
        strategies.forEach { strategy ->
            val leaves = Leaves()
            val refs = strategy.arrange(leaves.registry, isRtl = false).componentRefs
                as LayoutComponentRefs.WithSplitPanes

            SwingUtilities.invokeAndWait { refs.updateExtraOutputVisibility(true, leaves.extra) }
            flush()
            assertTrue(leaves.extra.isVisible, "${strategy.id}: Extra Output shows")
            assertTrue(refs.extraSplit.dividerSize > 0, "${strategy.id}: the divider comes back with it")

            SwingUtilities.invokeAndWait { refs.updateExtraOutputVisibility(false, leaves.extra) }
            flush()
            assertFalse(leaves.extra.isVisible, "${strategy.id}: Extra Output hides")
            assertEquals(0, refs.extraSplit.dividerSize, "${strategy.id}: no divider region is left behind")
        }
    }

    @Test
    fun `a hidden Extra Output leaves no empty band below the results in any layout`() {
        strategies.forEach { strategy ->
            val leaves = Leaves()
            val arranged = strategy.arrange(leaves.registry, isRtl = false)
            val refs = arranged.componentRefs as LayoutComponentRefs.WithSplitPanes

            SwingUtilities.invokeAndWait { refs.updateExtraOutputVisibility(false, leaves.extra) }
            flush()
            SwingUtilities.invokeAndWait {
                refs.extraSplit.setSize(1200, 800)
                layoutTree(refs.extraSplit)
            }
            assertEquals(
                refs.extraSplit.height, refs.mainSplit.height,
                "${strategy.id}: the workspace takes the whole height when there is no Extra Output"
            )
        }
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    @Test
    fun `a right to left rebuild of every layout stays valid`() {
        val container = JPanel(BorderLayout())
        val leaves = Leaves()
        val manager = LayoutManager(leaves.registry, container)

        strategies.forEach { strategy ->
            listOf(true, false, true).forEach { rtl ->
                SwingUtilities.invokeAndWait { manager.switchLayout(strategy.id, rtl) }
                flush()
                listOf(leaves.input, leaves.extra).forEach { leaf ->
                    assertEquals(1, count(container, leaf), "${strategy.id} rtl=$rtl lost or duplicated a pane")
                }
            }
        }
    }

    @Test
    fun `focus targets are plainly visible in every layout, never hidden behind a tab`() {
        strategies.forEach { strategy ->
            val leaves = Leaves()
            val root = strategy.arrange(leaves.registry, isRtl = false).rootComponent
            val output = if (strategy === ComparisonLayout) leaves.board else leaves.output

            listOf(leaves.input, output, leaves.extra).forEach { target ->
                assertNotNull(target.parent, "${strategy.id}: every focus target is mounted")
                assertTrue(target.isVisible)
                val chain = ancestors(target)
                assertTrue(root in chain, "${strategy.id}: target sits under the layout root")
                assertTrue(chain.none { it is JTabbedPane }, "${strategy.id}: no tab may hide a focus target")
            }
        }
    }
}
