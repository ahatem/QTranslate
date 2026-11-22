package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.LayoutPresetIds
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
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

    /** One window per pane arrangement, every other value at its default. */
    private val layouts = PaneLayout.entries.map { WindowLayout(panes = it) }

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
            LayoutManager.getAvailableLayouts()
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

        assertSame(LayoutType.CLASSIC, LayoutType.fromId(LayoutPresetIds.LEGACY_COMPACT))
        assertSame(LayoutType.CLASSIC, LayoutType.fromId("from_a_newer_version"))
    }

    @Test
    fun `every layout arranges its panes in split panes`() {
        layouts.forEach { layout ->
            val refs = SlotLayout.arrange(Leaves().registry, layout).componentRefs
            assertIs<LayoutComponentRefs.WithSplitPanes>(refs, "${layout.panes} must be built from split panes")
        }
    }

    @Test
    fun `switching between any two layouts keeps each pane attached once and the same instance`() {
        val container = JPanel(BorderLayout())
        val leaves = Leaves()
        val manager = LayoutManager(leaves.registry, container)
        val original = leaves.all.toList()

        layouts.flatMap { from -> layouts.map { to -> from to to } }.forEach { (from, to) ->
            SwingUtilities.invokeAndWait { manager.switchLayout(from) }
            flush()
            SwingUtilities.invokeAndWait { manager.switchLayout(to) }
            flush()
            listOf(leaves.all[0], leaves.input, leaves.all[2], leaves.extra, leaves.all[7]).forEach { leaf ->
                assertEquals(1, count(container, leaf), "${from.panes} to ${to.panes} lost or duplicated a pane")
            }
        }
        assertEquals(original, leaves.all, "the canonical panes are never replaced")
    }

    @Test
    fun `Extra Output shows and hides cleanly in every layout`() {
        layouts.forEach { layout ->
            val leaves = Leaves()
            val refs = SlotLayout.arrange(leaves.registry, layout).componentRefs
                as LayoutComponentRefs.WithSplitPanes

            SwingUtilities.invokeAndWait { refs.updateExtraOutputVisibility(true, leaves.extra) }
            flush()
            assertTrue(leaves.extra.isVisible, "${layout.panes}: Extra Output shows")
            assertTrue(refs.extraSplit.dividerSize > 0, "${layout.panes}: the divider comes back with it")

            SwingUtilities.invokeAndWait { refs.updateExtraOutputVisibility(false, leaves.extra) }
            flush()
            assertFalse(leaves.extra.isVisible, "${layout.panes}: Extra Output hides")
            assertEquals(0, refs.extraSplit.dividerSize, "${layout.panes}: no divider region is left behind")
        }
    }

    @Test
    fun `a hidden Extra Output leaves no empty band below the results in any layout`() {
        layouts.forEach { layout ->
            val leaves = Leaves()
            val arranged = SlotLayout.arrange(leaves.registry, layout)
            val refs = arranged.componentRefs as LayoutComponentRefs.WithSplitPanes

            SwingUtilities.invokeAndWait { refs.updateExtraOutputVisibility(false, leaves.extra) }
            flush()
            SwingUtilities.invokeAndWait {
                refs.extraSplit.setSize(1200, 800)
                layoutTree(refs.extraSplit)
            }
            assertEquals(
                refs.extraSplit.height, refs.mainSplit.height,
                "${layout.panes}: the workspace takes the whole height when there is no Extra Output"
            )
        }
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    @Test
    fun `the selector row keeps its preferred height when the bottom bar has spare space`() {
        val leaves = Leaves()
        val selector = leaves.all[6].apply { preferredSize = Dimension(300, 24) }
        val status = leaves.all[7].apply { preferredSize = Dimension(300, 20) }
        // Measured through the whole window rather than the footer alone: the bottom slot brings
        // the side padding, so the footer by itself no longer shows it.
        val root = SlotLayout.arrange(leaves.registry, WindowLayout.DEFAULT).rootComponent

        SwingUtilities.invokeAndWait {
            root.setSize(800, 600)
            layoutTree(root as Container)
        }

        assertEquals(24, selector.height)
        assertEquals(20, status.height)
        val selectorX = SwingUtilities.convertPoint(selector, 0, 0, root).x
        assertEquals(UISpacing.PADDING, selectorX)
        assertEquals(UISpacing.PADDING, root.width - selectorX - selector.width)
    }

    @Test
    fun `a right to left rebuild of every layout stays valid`() {
        val container = JPanel(BorderLayout())
        val leaves = Leaves()
        val manager = LayoutManager(leaves.registry, container)

        layouts.forEach { layout ->
            listOf(true, false, true).forEach { rtl ->
                SwingUtilities.invokeAndWait { manager.switchLayout(layout, isRtl = rtl) }
                flush()
                listOf(leaves.input, leaves.extra).forEach { leaf ->
                    assertEquals(1, count(container, leaf), "${layout.panes} rtl=$rtl lost or duplicated a pane")
                }
            }
        }
    }

    @Test
    fun `focus targets are plainly visible in every layout, never hidden behind a tab`() {
        layouts.forEach { layout ->
            val leaves = Leaves()
            val root = SlotLayout.arrange(leaves.registry, layout).rootComponent
            val output = if (layout.panes == PaneLayout.COMPARISON) leaves.board else leaves.output

            listOf(leaves.input, output, leaves.extra).forEach { target ->
                assertNotNull(target.parent, "${layout.panes}: every focus target is mounted")
                assertTrue(target.isVisible)
                val chain = ancestors(target)
                assertTrue(root in chain, "${layout.panes}: target sits under the layout root")
                assertTrue(chain.none { it is JTabbedPane }, "${layout.panes}: no tab may hide a focus target")
            }
        }
    }

    @Test
    fun `a shared row puts the history controls first and gives the languages the row`() {
        // History and languages in one slot with shared rows: the history bar keeps every button
        // and stops being a row. The order is fixed (history, languages, services) and the row
        // starts at LINE_START, so history leads and the language controls take the rest.
        layouts.forEach { base ->
            val leaves = Leaves()
            val layout = base.copy(history = RegionSlot.TOP, languages = RegionSlot.TOP, shareRows = true)
            SlotLayout.arrange(leaves.registry, layout)
            flush()

            val history = leaves.registry.historyBar
            val language = leaves.registry.languageBar
            val row = history.parent
            assertSame(language.parent, row, "${layout.panes}: both belong to one row")
            val border = row?.layout as? BorderLayout
            assertNotNull(border, "${layout.panes}: the shared row places its members")
            assertEquals(BorderLayout.CENTER, border.getConstraints(language), "${layout.panes}: language takes the row")
            assertEquals(BorderLayout.LINE_START, border.getConstraints(history), "${layout.panes}: history leads it")
        }
    }

    @Test
    fun `without shared rows the history controls keep a row of their own`() {
        // The other half of the same promise: the language row must not quietly become the history
        // row for everyone who never asked for one row.
        layouts.forEach { base ->
            val leaves = Leaves()
            val layout = base.copy(history = RegionSlot.TOP, languages = RegionSlot.TOP, shareRows = false)
            SlotLayout.arrange(leaves.registry, layout)
            flush()

            val history = leaves.registry.historyBar
            val language = leaves.registry.languageBar
            val sharesLanguageRow = history.parent === language.parent &&
                (history.parent?.layout as? BorderLayout)?.getConstraints(language) == BorderLayout.CENTER
            assertFalse(sharesLanguageRow, "${layout.panes}: separate rows keep the regions apart")
        }
    }
}
