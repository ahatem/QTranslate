package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.SecondResultPlacement
import com.github.ahatem.qtranslate.core.settings.data.ServiceWidget
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowRegion
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.GridBagLayout
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The structure [SlotLayout] builds: each slot is one container in one place whatever the panes,
 * the rows inside a slot keep the order history, languages, services, and nothing is placed by a
 * compass constraint that a right-to-left window would leave unmirrored.
 */
class SlotLayoutStructureTest {

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private class Window(val registry: ComponentRegistry, val root: JComponent) {
        private val bottomBar = registry.statusBar.parent as Container
        private val content = (root.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER) as Container

        /** The TOP slot's container: the north edge of the padded content. */
        val top: Component? get() = (content.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH)

        /** The BOTTOM slot's container: above the status bar in the footer. */
        val bottom: Component? get() = (bottomBar.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH)

        /** The ABOVE_RESULTS slot's container: the header of the section that holds the results. */
        val aboveResults: Component?
            get() {
                val results = if (SwingUtilities.isDescendingFrom(registry.compareBoard, root)) {
                    registry.compareBoard
                } else {
                    registry.outputPanel
                }
                val section = generateSequence(results.parent) { it.parent }
                    .firstOrNull { it.layout is BorderLayout && it.parent != null &&
                        (it.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
                            ?.let { center -> SwingUtilities.isDescendingFrom(results, center) } == true &&
                        it !== content && it !== root }
                return section?.let { (it.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH) }
            }

        fun componentOf(region: WindowRegion): JComponent = when (region) {
            WindowRegion.HISTORY -> registry.historyBar
            WindowRegion.LANGUAGES -> registry.languageBar
            WindowRegion.SERVICES -> registry.translatorSelector
        }

        fun slotContainer(slot: RegionSlot): Component? = when (slot) {
            RegionSlot.TOP -> top
            RegionSlot.ABOVE_RESULTS -> aboveResults
            RegionSlot.BOTTOM -> bottom
            RegionSlot.HIDDEN -> null
        }

        /** The row of [region] inside its slot column: the column's direct child that holds it. */
        fun rowOf(region: WindowRegion, slot: RegionSlot): Component? {
            val column = slotContainer(slot) as? Container ?: return null
            return column.components.firstOrNull { SwingUtilities.isDescendingFrom(componentOf(region), it) }
        }

        fun gridyOf(region: WindowRegion, slot: RegionSlot): Int {
            val column = slotContainer(slot) as Container
            val row = assertNotNull(rowOf(region, slot), "$region has a row in $slot")
            return (column.layout as GridBagLayout).getConstraints(row).gridy
        }
    }

    private fun build(layout: WindowLayout, isRtl: Boolean = false, bell: Boolean = false): Window = onEdt {
        val leaves = List(8) { JPanel() }
        val registry = ComponentRegistry(
            historyBar = leaves[0], inputPanel = leaves[1], languageBar = leaves[2],
            outputPanel = leaves[3], compareBoard = leaves[4], extraOutputPanel = leaves[5],
            translatorSelector = leaves[6], statusBar = leaves[7],
            notificationButton = if (bell) JButton() else null,
        )
        Window(registry, SlotLayout.arrange(registry, layout, layout.panes, isRtl).rootComponent)
    }

    /**
     * [region] in [slot] and nothing else placed anywhere else. The language row never hides, so
     * when another region is under test the language row shares its slot rather than leaving it.
     */
    private fun only(region: WindowRegion, slot: RegionSlot, panes: PaneLayout): WindowLayout {
        val hidden = RegionSlot.HIDDEN
        return WindowLayout(
            panes = panes,
            history = if (region == WindowRegion.HISTORY) slot else hidden,
            languages = slot,
            services = if (region == WindowRegion.SERVICES) slot else hidden,
        )
    }

    @Test
    fun `each region lands in the container of its slot, whatever the panes`() {
        val visibleSlots = RegionSlot.entries - RegionSlot.HIDDEN
        PaneLayout.entries.forEach { panes ->
            WindowRegion.entries.forEach regions@{ region ->
                // The board header switches services under comparison; the selector is never drawn.
                if (panes == PaneLayout.COMPARISON && region == WindowRegion.SERVICES) return@regions
                visibleSlots.forEach { slot ->
                    val window = build(only(region, slot, panes))
                    onEdt {
                        val component = window.componentOf(region)
                        val container = assertNotNull(window.slotContainer(slot), "$panes: the $slot slot exists for $region")
                        assertTrue(SwingUtilities.isDescendingFrom(component, container), "$panes: $region is in $slot")
                        (visibleSlots - slot).forEach { other ->
                            assertNull(window.slotContainer(other), "$panes: the empty $other slot is not built for $region in $slot")
                        }
                        assertTrue(component.isVisible)
                    }
                }
            }
        }
    }

    @Test
    fun `above results sits over the results, never over the input, in stacked and comparison`() {
        listOf(PaneLayout.STACKED, PaneLayout.COMPARISON).forEach { panes ->
            val window = build(only(WindowRegion.LANGUAGES, RegionSlot.ABOVE_RESULTS, panes))
            onEdt {
                val header = assertNotNull(window.aboveResults, "$panes has a header over the results")
                assertTrue(SwingUtilities.isDescendingFrom(window.registry.languageBar, header))
                assertTrue(!SwingUtilities.isDescendingFrom(window.registry.inputPanel, header.parent), "$panes: not over the input")
            }
        }
    }

    @Test
    fun `above results in side by side is a full-width row directly above both columns`() {
        val window = build(only(WindowRegion.LANGUAGES, RegionSlot.ABOVE_RESULTS, PaneLayout.SIDE_BY_SIDE))
        onEdt {
            val header = assertNotNull(window.aboveResults, "the row exists")
            assertTrue(SwingUtilities.isDescendingFrom(window.registry.languageBar, header))
            val section = header.parent as Container
            assertEquals(
                BorderLayout.NORTH, (section.layout as BorderLayout).getConstraints(header),
                "the row sits at the top of the results area",
            )
            // Both columns are under the row, so the row spans the input column as well.
            val columns = (section.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
            assertTrue(SwingUtilities.isDescendingFrom(window.registry.inputPanel, columns), "the input column is under the row")
            assertTrue(SwingUtilities.isDescendingFrom(window.registry.outputPanel, columns), "the output column is under the row")
            assertTrue(!SwingUtilities.isDescendingFrom(window.registry.inputPanel, header))
            assertTrue(!SwingUtilities.isDescendingFrom(window.registry.outputPanel, header))
        }
    }

    @Test
    fun `above results in side by side sits below any top rows`() {
        val window = build(
            WindowLayout(
                panes = PaneLayout.SIDE_BY_SIDE, history = RegionSlot.TOP,
                languages = RegionSlot.ABOVE_RESULTS, services = RegionSlot.BOTTOM,
            )
        )
        onEdt {
            val top = assertNotNull(window.top)
            val header = assertNotNull(window.aboveResults)
            assertTrue(SwingUtilities.isDescendingFrom(window.registry.historyBar, top))
            // The top rows are the content's north edge; the row over the results is inside the centre.
            val content = window.registry.statusBar.parent.parent as Container
            assertTrue(!SwingUtilities.isDescendingFrom(header, top))
            val center = (content.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
            assertTrue(SwingUtilities.isDescendingFrom(header, center), "below the top rows, above the columns")
        }
    }

    @Test
    fun `rows in one slot keep the order history, languages, services`() {
        PaneLayout.entries.filter { it != PaneLayout.COMPARISON }.forEach { panes ->
            (RegionSlot.entries - RegionSlot.HIDDEN).forEach { slot ->
                val window = build(WindowLayout(panes = panes, history = slot, languages = slot, services = slot, shareRows = false))
                onEdt {
                    assertEquals(
                        listOf(0, 1, 2),
                        WindowRegion.entries.map { window.gridyOf(it, slot) },
                        "$panes, $slot",
                    )
                }
            }
        }
    }

    @Test
    fun `the service selector keeps its own row beside a history row, whichever widget it is`() {
        ServiceWidget.entries.forEach { widget ->
            val window = build(
                WindowLayout(
                    history = RegionSlot.TOP,
                    languages = RegionSlot.BOTTOM,
                    services = RegionSlot.TOP,
                    serviceWidget = widget,
                    shareRows = true,
                )
            )
            onEdt {
                val history = window.rowOf(WindowRegion.HISTORY, RegionSlot.TOP)
                val services = window.rowOf(WindowRegion.SERVICES, RegionSlot.TOP)
                assertTrue(history !== services, "$widget: separate rows")
                assertEquals(0, window.gridyOf(WindowRegion.HISTORY, RegionSlot.TOP), "$widget")
                assertEquals(1, window.gridyOf(WindowRegion.SERVICES, RegionSlot.TOP), "$widget: below history")
            }
        }
    }

    @Test
    fun `the service selector never shares a row`() {
        ServiceWidget.entries.forEach { widget ->
            val window = build(
                WindowLayout(
                    history = RegionSlot.TOP,
                    languages = RegionSlot.TOP,
                    services = RegionSlot.TOP,
                    serviceWidget = widget,
                    shareRows = true,
                )
            )
            onEdt {
                val shared = window.rowOf(WindowRegion.HISTORY, RegionSlot.TOP)
                assertEquals(shared, window.rowOf(WindowRegion.LANGUAGES, RegionSlot.TOP), "$widget: history and languages share")
                val selector = window.rowOf(WindowRegion.SERVICES, RegionSlot.TOP)
                assertTrue(selector !== shared, "$widget: the selector has its own row")
                assertEquals(1, window.gridyOf(WindowRegion.SERVICES, RegionSlot.TOP), "$widget: below the shared row")
            }
        }
    }

    @Test
    fun `the notification button ends whichever row holds history`() {
        listOf(false, true).forEach { share ->
            val layout = WindowLayout(
                history = RegionSlot.TOP, languages = RegionSlot.TOP, shareRows = share, showStatusBar = false,
            )
            val window = build(layout, bell = true)
            onEdt {
                val bell = assertNotNull(window.registry.notificationButton)
                val row = bell.parent as Container
                assertSame(row, window.rowOf(WindowRegion.HISTORY, RegionSlot.TOP), "share=$share: the bell is in the history row")
                assertSame(bell, (row.layout as BorderLayout).getLayoutComponent(BorderLayout.LINE_END), "share=$share")
                assertEquals(share, SwingUtilities.isDescendingFrom(window.registry.languageBar, row), "share=$share")
            }
        }
    }

    @Test
    fun `the notification button is left to the status bar while the bar is shown`() {
        val window = build(WindowLayout(history = RegionSlot.TOP, showStatusBar = true), bell = true)
        onEdt {
            val bell = assertNotNull(window.registry.notificationButton)
            assertNull(bell.parent, "the window leaves it to the status bar")
        }
    }

    @Test
    fun `no arrangement places anything by a compass edge`() {
        var checked = 0
        PaneLayout.entries.forEach { panes ->
            RegionSlot.entries.forEach { history ->
                WindowRegion.LANGUAGES.allowedSlots.forEach { languages ->
                    RegionSlot.entries.forEach { services ->
                        ServiceWidget.entries.forEach { widget ->
                            listOf(false, true).forEach { share ->
                                listOf(false, true).forEach { rtl ->
                                    val layout = WindowLayout(
                                        panes = panes, history = history, languages = languages,
                                        services = services, serviceWidget = widget, shareRows = share,
                                    )
                                    val window = build(layout, isRtl = rtl)
                                    onEdt {
                                        descendants(window.root).filterIsInstance<Container>()
                                            .filter { it.layout is BorderLayout }
                                            .forEach { container ->
                                                val border = container.layout as BorderLayout
                                                listOf(BorderLayout.WEST, BorderLayout.EAST).forEach { edge ->
                                                    border.getLayoutComponent(edge)?.let {
                                                        fail("$layout rtl=$rtl: ${it.javaClass.simpleName} is placed at $edge")
                                                    }
                                                }
                                            }
                                    }
                                    checked++
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(3 * 4 * 3 * 4 * 2 * 2 * 2, checked)
    }

    @Test
    fun `no placement of the second result uses a compass edge either`() {
        var checked = 0
        PaneLayout.entries.forEach { panes ->
            SecondResultPlacement.entries.forEach { placement ->
                listOf(false, true).forEach { rtl ->
                    val window = build(
                        WindowLayout(
                            panes = panes, history = RegionSlot.TOP, languages = RegionSlot.ABOVE_RESULTS,
                            services = RegionSlot.BOTTOM, secondResult = placement,
                        ),
                        isRtl = rtl,
                    )
                    onEdt {
                        descendants(window.root).filterIsInstance<Container>()
                            .mapNotNull { it.layout as? BorderLayout }
                            .forEach { border ->
                                listOf(BorderLayout.WEST, BorderLayout.EAST).forEach { edge ->
                                    assertNull(border.getLayoutComponent(edge), "$panes / $placement rtl=$rtl: $edge is used")
                                }
                            }
                    }
                    checked++
                }
            }
        }
        assertEquals(3 * 3 * 2, checked)
    }

    // ---- The second result ----

    /** A window built with its refs, so the second result can be shown and hidden as the window does. */
    private class Built(val window: Window, val refs: LayoutComponentRefs) {
        val extra: JComponent get() = window.registry.extraOutputPanel
    }

    private fun buildWithRefs(layout: WindowLayout, isRtl: Boolean = false): Built = onEdt {
        // Panes with a size of their own, as the real ones have: a zero-sized stub gives a split
        // nothing to share out when the second result comes back.
        val leaves = List(8) { index ->
            JPanel().apply { preferredSize = if (index in listOf(1, 3, 4, 5)) Dimension(300, 200) else Dimension(300, 30) }
        }
        val registry = ComponentRegistry(
            historyBar = leaves[0], inputPanel = leaves[1], languageBar = leaves[2],
            outputPanel = leaves[3], compareBoard = leaves[4], extraOutputPanel = leaves[5],
            translatorSelector = leaves[6], statusBar = leaves[7],
        )
        val arranged = SlotLayout.arrange(registry, layout, layout.panes, isRtl)
        Built(Window(registry, arranged.rootComponent), arranged.componentRefs)
    }

    private fun showSecondResult(built: Built, visible: Boolean) {
        onEdt {
            built.extra.isVisible = visible
            built.refs.updateExtraOutputVisibility(visible, built.extra)
        }
        // The update is queued; drain it, and anything it queues in turn.
        repeat(3) { onEdt { } }
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun sized(built: Built, width: Int = 1400, height: Int = 800) {
        onEdt { built.window.root.setSize(width, height) }
        repeat(3) {
            onEdt { layoutTree(built.window.root) }
            onEdt { }
        }
    }

    /** One layout pass, as a window does after a revalidate. A second pass would reuse a stale divider. */
    private fun relayout(built: Built) = onEdt { layoutTree(built.window.root) }

    private fun splitsAbove(component: Component): List<JSplitPane> =
        generateSequence(component.parent) { it.parent }.filterIsInstance<JSplitPane>().toList()

    /** Where [component] is, in [root]'s coordinates. */
    private fun xIn(root: Component, component: Component): Int =
        SwingUtilities.convertPoint(component.parent, component.location, root).x

    private fun yIn(root: Component, component: Component): Int =
        SwingUtilities.convertPoint(component.parent, component.location, root).y

    private fun layoutOf(panes: PaneLayout, placement: SecondResultPlacement) =
        WindowLayout(
            panes = panes, history = RegionSlot.HIDDEN, languages = RegionSlot.ABOVE_RESULTS,
            services = RegionSlot.HIDDEN, secondResult = placement,
        )

    @Test
    fun `the second result is built where its placement says, for every pane layout`() {
        listOf(false, true).forEach { rtl ->
            PaneLayout.entries.forEach { panes ->
                SecondResultPlacement.entries.forEach { placement ->
                    val layout = layoutOf(panes, placement)
                    val effective = layout.effectiveSecondResult(panes)
                    val built = buildWithRefs(layout, rtl)
                    showSecondResult(built, true)
                    sized(built)
                    val context = "$panes / $placement (built as $effective) rtl=$rtl"
                    val registry = built.window.registry
                    val root = built.window.root
                    onEdt {
                        assertTrue(SwingUtilities.isDescendingFrom(built.extra, root), context)
                        assertTrue(built.extra.isVisible, context)
                        assertTrue(built.extra.width > 0 && built.extra.height > 0, "$context: it has room")
                        val nearest = assertNotNull(splitsAbove(built.extra).firstOrNull(), "$context: a split holds it")
                        val outputIn = { c: Component -> SwingUtilities.isDescendingFrom(registry.outputPanel, c) }
                        val board = if (panes == PaneLayout.COMPARISON) registry.compareBoard else registry.outputPanel
                        when (effective) {
                            SecondResultPlacement.BELOW -> {
                                // Full width under the panes: the second child of a vertical split whose
                                // first child holds the results and the input.
                                assertEquals(JSplitPane.VERTICAL_SPLIT, nearest.orientation, context)
                                assertTrue(SwingUtilities.isDescendingFrom(built.extra, nearest.bottomComponent), context)
                                assertTrue(SwingUtilities.isDescendingFrom(board, nearest.topComponent), context)
                                assertTrue(SwingUtilities.isDescendingFrom(registry.inputPanel, nearest.topComponent), context)
                                assertTrue(yIn(root, built.extra) > yIn(root, board), "$context: below the results")
                                val widest = if (panes == PaneLayout.SIDE_BY_SIDE) {
                                    registry.inputPanel.width + registry.outputPanel.width
                                } else {
                                    board.width
                                }
                                assertTrue(built.extra.width >= widest, "$context: as wide as everything above it")
                            }
                            SecondResultPlacement.WITH_RESULT -> if (panes == PaneLayout.STACKED) {
                                // Beside the result, under the input.
                                assertEquals(JSplitPane.HORIZONTAL_SPLIT, nearest.orientation, context)
                                assertTrue(
                                    outputIn(nearest.leftComponent) != outputIn(nearest.rightComponent),
                                    "$context: the output is on one side of it",
                                )
                                assertTrue(yIn(root, registry.inputPanel) < yIn(root, registry.outputPanel), "$context: input above both")
                                assertEquals(yIn(root, registry.outputPanel), yIn(root, built.extra), "$context: level with the result")
                                assertEquals(
                                    rtl, xIn(root, built.extra) < xIn(root, registry.outputPanel),
                                    "$context: the second result follows the result in reading order",
                                )
                            } else {
                                // Under the result, in the result column; the input keeps the full height.
                                assertEquals(JSplitPane.VERTICAL_SPLIT, nearest.orientation, context)
                                assertTrue(SwingUtilities.isDescendingFrom(registry.outputPanel, nearest.topComponent), context)
                                assertTrue(SwingUtilities.isDescendingFrom(built.extra, nearest.bottomComponent), context)
                                assertFalse(SwingUtilities.isDescendingFrom(registry.inputPanel, nearest), "$context: the input is beside the column")
                                assertEquals(xIn(root, registry.outputPanel), xIn(root, built.extra), "$context: same column")
                                assertTrue(yIn(root, built.extra) > yIn(root, registry.outputPanel), context)
                                assertTrue(
                                    registry.inputPanel.height >= registry.outputPanel.height + built.extra.height,
                                    "$context: the input spans the whole height of the column",
                                )
                            }
                            SecondResultPlacement.OWN_COLUMN -> {
                                // Three columns, level with each other, in reading order.
                                assertEquals(JSplitPane.HORIZONTAL_SPLIT, nearest.orientation, context)
                                assertTrue(
                                    outputIn(nearest.leftComponent) != outputIn(nearest.rightComponent),
                                    "$context: it shares a split with the output",
                                )
                                val three = listOf(registry.inputPanel, registry.outputPanel, built.extra)
                                val ys = three.map { yIn(root, it) }
                                assertEquals(1, ys.toSet().size, "$context: three columns on one level: $ys")
                                val xs = three.map { xIn(root, it) }
                                assertEquals(
                                    if (rtl) xs.sortedDescending() else xs.sorted(), xs,
                                    "$context: input, output, second result in reading order",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a placement the panes cannot show is built as below`() {
        listOf(false, true).forEach { rtl ->
            listOf(
                PaneLayout.STACKED to SecondResultPlacement.OWN_COLUMN,
                PaneLayout.COMPARISON to SecondResultPlacement.WITH_RESULT,
                PaneLayout.COMPARISON to SecondResultPlacement.OWN_COLUMN,
            ).forEach { (panes, asked) ->
                val below = buildWithRefs(layoutOf(panes, SecondResultPlacement.BELOW), rtl)
                val fallback = buildWithRefs(layoutOf(panes, asked), rtl)
                listOf(below, fallback).forEach { showSecondResult(it, true); sized(it) }
                onEdt {
                    fun boundsOf(b: Built): List<Rectangle> {
                        val registry = b.window.registry
                        val results = if (panes == PaneLayout.COMPARISON) registry.compareBoard else registry.outputPanel
                        return listOf(b.extra, registry.inputPanel, results)
                            .map { SwingUtilities.convertRectangle(it.parent, it.bounds, b.window.root) }
                    }
                    assertEquals(boundsOf(below), boundsOf(fallback), "$panes asked for $asked, rtl=$rtl")
                }
            }
        }
    }

    @Test
    fun `a hidden second result leaves nothing showing and no divider, in every structure`() {
        listOf(false, true).forEach { rtl ->
            PaneLayout.entries.forEach { panes ->
                SecondResultPlacement.entries.forEach { placement ->
                    val built = buildWithRefs(layoutOf(panes, placement), rtl)
                    val context = "$panes / $placement rtl=$rtl"
                    sized(built)
                    showSecondResult(built, false)
                    relayout(built)
                    onEdt {
                        val registry = built.window.registry
                        assertFalse(built.extra.isVisible, context)
                        val holder = assertNotNull(
                            generateSequence<Component>(built.extra) { it.parent }.firstOrNull { it.parent is JSplitPane },
                            "$context: the panel is held by a split",
                        )
                        val split = holder.parent as JSplitPane
                        assertFalse(holder.isVisible, "$context: whatever holds the panel is hidden with it")
                        assertEquals(0, split.dividerSize, "$context: no divider is left")
                        // The sibling takes the whole of the split along its axis, so there is no gap.
                        val sibling = listOfNotNull(split.leftComponent, split.rightComponent).single { it !== holder }
                        if (split.orientation == JSplitPane.HORIZONTAL_SPLIT) {
                            assertEquals(split.width, sibling.width, "$context: no gap beside it")
                        } else {
                            assertEquals(split.height, sibling.height, "$context: no gap under it")
                        }
                        val results = if (panes == PaneLayout.COMPARISON) registry.compareBoard else registry.outputPanel
                        assertTrue(results.width > 0 && results.height > 0, context)
                    }
                    // Showing it again brings the divider and the panel back.
                    showSecondResult(built, true)
                    relayout(built)
                    onEdt {
                        val split = splitsAbove(built.extra).first()
                        assertEquals(UISpacing.DIVIDER_SIZE, split.dividerSize, "$context: the divider returns")
                        assertTrue(
                            built.extra.isVisible && built.extra.width > 0 && built.extra.height > 0,
                            "$context: visible=${built.extra.isVisible} size=${built.extra.size} split=${split.size} loc=${split.dividerLocation}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the hidden three column layout is two plain columns again`() {
        val built = buildWithRefs(layoutOf(PaneLayout.SIDE_BY_SIDE, SecondResultPlacement.OWN_COLUMN))
        sized(built)
        showSecondResult(built, false)
        sized(built)
        onEdt {
            val registry = built.window.registry
            val pair = splitsAbove(registry.outputPanel).first { it.orientation == JSplitPane.HORIZONTAL_SPLIT && it.dividerSize > 0 }
            // The output has the whole of what the input leaves, and the two share the width evenly.
            assertEquals(pair.width, registry.inputPanel.width + pair.dividerSize + registry.outputPanel.width)
            assertTrue(
                kotlin.math.abs(registry.inputPanel.width - registry.outputPanel.width) <= 2,
                "input ${registry.inputPanel.width}, output ${registry.outputPanel.width}",
            )
        }
    }

    @Test
    fun `above results sits over the whole results area, the second result included when it shares it`() {
        PaneLayout.entries.forEach { panes ->
            SecondResultPlacement.entries.forEach { placement ->
                val layout = layoutOf(panes, placement)
                val shares = layout.effectiveSecondResult(panes) != SecondResultPlacement.BELOW
                val built = buildWithRefs(layout)
                onEdt {
                    val header = assertNotNull(built.window.aboveResults, "$panes / $placement")
                    val section = header.parent as Container
                    val center = (section.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
                    assertEquals(
                        shares, SwingUtilities.isDescendingFrom(built.extra, center),
                        "$panes / $placement: the second result is under the row only when it shares the results area",
                    )
                    assertTrue(SwingUtilities.isDescendingFrom(built.window.registry.languageBar, header))
                }
            }
        }
    }

    private fun descendants(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) root.components.forEach { yieldAll(descendants(it)) }
    }
}
