package com.github.ahatem.qtranslate.ui.swing.main.menus

import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.LayoutDisabledReason
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.SecondResultPlacement
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import java.awt.ComponentOrientation
import javax.swing.JMenuItem
import javax.swing.JRadioButtonMenuItem
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The second result's own menu: the popup of its header and the group the View menu embeds. */
class SecondResultMenuTest {

    private val strings = SecondResultMenuStrings(
        title = "Second result",
        optionName = { option, panes ->
            when (panes) {
                PaneLayout.STACKED -> when (option) {
                    SecondResultPlacement.BELOW -> "Below the result"
                    SecondResultPlacement.WITH_RESULT -> "Beside the result"
                    SecondResultPlacement.OWN_COLUMN -> "Column"
                }
                else -> option.name.lowercase()
            }
        },
        reason = { "because ${it.name}" },
    )

    private val written = mutableListOf<WindowLayout>()

    private fun menu(
        layout: WindowLayout,
        type: ExtraOutputType = ExtraOutputType.BackwardTranslate,
        panes: PaneLayout = layout.panes,
    ) = SecondResultMenu(layout, panes, type, strings) { written += it }

    private fun radiosOf(menu: SecondResultMenu) = menu.items(withHeading = false)
        .filterIsInstance<JRadioButtonMenuItem>().associateBy { it.name.removePrefix(SecondResultMenu.OPTION_PREFIX) }

    @Test
    fun `the popup is headed by the group name and offers one radio per placement`() {
        val items = menu(WindowLayout()).items()
        assertEquals("Second result", (items.first() as JMenuItem).text)
        assertFalse(items.first().isEnabled)
        assertEquals(3, items.filterIsInstance<JRadioButtonMenuItem>().size)
    }

    @Test
    fun `the labels follow the panes the second result is built with`() {
        val stacked = radiosOf(menu(WindowLayout(panes = PaneLayout.STACKED)))
        assertEquals("Below the result", stacked.getValue("BELOW").text)
        assertEquals("Beside the result", stacked.getValue("WITH_RESULT").text)
        val sideBySide = radiosOf(menu(WindowLayout(panes = PaneLayout.SIDE_BY_SIDE)))
        assertEquals("with_result", sideBySide.getValue("WITH_RESULT").text)
    }

    @Test
    fun `an effective pane layout that differs from the stored one decides the labels and the rules`() {
        // The window shows Stacked because the comparison it stored is not available.
        val layout = WindowLayout(panes = PaneLayout.COMPARISON, secondResult = SecondResultPlacement.WITH_RESULT)
        val options = radiosOf(menu(layout, panes = PaneLayout.STACKED))
        assertEquals("Beside the result", options.getValue("WITH_RESULT").text)
        assertTrue(options.getValue("WITH_RESULT").isSelected)
        assertTrue(options.getValue("WITH_RESULT").isEnabled)
    }

    @Test
    fun `a choice writes the whole layout once, and the choice already made writes nothing`() {
        val layout = WindowLayout(panes = PaneLayout.SIDE_BY_SIDE)
        val options = radiosOf(menu(layout))
        options.getValue("BELOW").doClick(0)
        assertEquals(emptyList(), written)
        options.getValue("OWN_COLUMN").doClick(0)
        assertEquals(listOf(layout.copy(secondResult = SecondResultPlacement.OWN_COLUMN)), written)
    }

    @Test
    fun `no second result chosen disables everything with one visible reason`() {
        val items = menu(WindowLayout(), type = ExtraOutputType.None).items()
        val radios = items.filterIsInstance<JRadioButtonMenuItem>()
        assertTrue(radios.none { it.isEnabled })
        radios.forEach { assertEquals("because ${LayoutDisabledReason.SECOND_RESULT_NEEDS_TYPE.name}", it.toolTipText) }
        val reasons = items.filter { it.name == SecondResultMenu.REASON }
        assertEquals(1, reasons.size, "one line, not one per choice")
        assertEquals("because ${LayoutDisabledReason.SECOND_RESULT_NEEDS_TYPE.name}", (reasons.single() as JMenuItem).text)
        radios.forEach { it.doClick(0) }
        assertEquals(emptyList(), written)
    }

    @Test
    fun `the stored choice survives a window that cannot show it`() {
        val layout = WindowLayout(panes = PaneLayout.COMPARISON, secondResult = SecondResultPlacement.OWN_COLUMN)
        val shown = radiosOf(menu(layout))
        assertTrue(shown.getValue("BELOW").isSelected, "the menu shows what is built")
        assertEquals(SecondResultPlacement.OWN_COLUMN, layout.secondResult, "and the stored value is untouched")
        assertEquals(emptyList(), written)
    }

    @Test
    fun `the header popup is filled when it opens and follows the window then`() {
        var current: SecondResultPopup.Current? = null
        val popup = SecondResultPopup({ current }, { strings }, { written += it })
        popup.refresh()
        assertEquals(0, popup.componentCount, "nothing before the first render")

        current = SecondResultPopup.Current(
            WindowLayout(panes = PaneLayout.STACKED), PaneLayout.STACKED, ExtraOutputType.Rewrite, isRtl = true,
        )
        popup.refresh()
        // The heading, the three placements, and the one reason a column of its own is unavailable.
        assertEquals(5, popup.componentCount)
        assertEquals(ComponentOrientation.RIGHT_TO_LEFT, popup.componentOrientation)

        current = current!!.copy(extraOutputType = ExtraOutputType.None, isRtl = false)
        popup.refresh()
        val radios = popup.components.filterIsInstance<JRadioButtonMenuItem>()
        assertTrue(radios.none { it.isEnabled })
        assertNotNull(popup.components.firstOrNull { it.name == SecondResultMenu.REASON })
    }

    @Test
    fun `the submenu has no heading of its own and carries its name`() {
        val submenu = menu(WindowLayout()).menu()
        assertEquals(SecondResultMenu.SUBMENU, submenu.name)
        assertEquals("Second result", submenu.text)
        assertTrue(submenu.menuComponents.none { it.name == SecondResultMenu.HEADING })
        SwingUtilities.invokeAndWait { }
    }
}
