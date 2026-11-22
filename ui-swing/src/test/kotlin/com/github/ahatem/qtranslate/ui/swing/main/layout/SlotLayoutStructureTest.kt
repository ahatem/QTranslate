package com.github.ahatem.qtranslate.ui.swing.main.layout

import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.ServiceWidget
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.WindowRegion
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun only(region: WindowRegion, slot: RegionSlot, panes: PaneLayout): WindowLayout {
        val hidden = RegionSlot.HIDDEN
        return WindowLayout(
            panes = panes,
            history = if (region == WindowRegion.HISTORY) slot else hidden,
            languages = if (region == WindowRegion.LANGUAGES) slot else hidden,
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
    fun `above results sits over the results, never over the input`() {
        PaneLayout.entries.forEach { panes ->
            val window = build(only(WindowRegion.LANGUAGES, RegionSlot.ABOVE_RESULTS, panes))
            onEdt {
                val header = assertNotNull(window.aboveResults, "$panes has a header over the results")
                assertTrue(SwingUtilities.isDescendingFrom(window.registry.languageBar, header))
                assertTrue(!SwingUtilities.isDescendingFrom(window.registry.inputPanel, header.parent), "$panes: not over the input")
            }
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
                RegionSlot.entries.forEach { languages ->
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
        assertEquals(3 * 4 * 4 * 4 * 2 * 2 * 2, checked)
    }

    private fun descendants(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) root.components.forEach { yieldAll(descendants(it)) }
    }
}
