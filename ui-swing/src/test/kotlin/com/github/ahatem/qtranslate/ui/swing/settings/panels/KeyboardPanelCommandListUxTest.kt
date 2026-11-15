package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.nio.file.Files
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The command list is a scroll pane inside the settings page's scroll pane, and it is the page's
 * tallest block. Both of those are ways for a settings screen to become unpleasant, so they are
 * held here rather than left to review:
 *
 *  - the list must keep a bounded height and scroll its own contents, instead of growing to
 *    forty-odd rows and pushing the page taller with it;
 *  - a wheel gesture must be able to leave the list when the list has nothing left to scroll,
 *    which Swing's scroll pane does not do on its own — it consumes every wheel event it sees,
 *    including the ones it cannot act on;
 *  - changing or clearing a shortcut must be reachable from the row it applies to, not from two
 *    buttons under the whole list.
 */
class KeyboardPanelCommandListUxTest {
    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-kbd-list").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        UIScale.setZoomFactor(1f)
        scopes.forEach { it.cancel() }
    }

    private fun store(configuration: Configuration = Configuration.DEFAULT): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-kbd-list-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(configuration)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, configuration)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** A panel as the settings dialog shows it: a page inside a scroll pane. */
    private class Page(
        val settings: SettingsStore,
        val panel: KeyboardPanel,
        val outer: JScrollPane,
    ) {
        val table: JTable get() = panel.tableForTest()
        val list: JScrollPane get() = panel.listScrollForTest()
    }

    private fun page(
        configuration: Configuration = Configuration.DEFAULT,
        recorder: ((HotkeyAction, HotkeyBinding?) -> HotkeyBinding?)? = null,
        withOuter: Boolean = true,
    ): Page {
        val settings = store(configuration)
        val panel = onEdt { KeyboardPanel(settings, localizer, recorder = recorder) }
        onEdt { panel.render(settings.state.value) }
        val outer = onEdt {
            JScrollPane(panel).apply {
                if (withOuter) {
                    setSize(UIScale.scale(700), UIScale.scale(320))
                    verticalScrollBar.apply {
                        minimum = 0
                        maximum = 1200
                        visibleAmount = 320
                        unitIncrement = 16
                        value = 400
                    }
                }
            }
        }
        onEdt { outer.updateUI() }
        return Page(settings, panel, outer)
    }

    private fun scrollBar(min: Int, max: Int, visible: Int, value: Int): JScrollBar =
        JScrollBar().apply {
            minimum = min
            maximum = max
            visibleAmount = visible
            this.value = value
        }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    @Test
    fun `the list keeps a bounded height instead of the height of every row`() {
        val page = page()
        val scroll = page.list

        val contentHeight = onEdt {
            (0 until page.table.rowCount).sumOf { page.table.getRowHeight(it) }
        }
        assertTrue(
            contentHeight > scroll.preferredSize.height,
            "the rows are taller than the viewport, so the list has to scroll ($contentHeight in ${scroll.preferredSize.height})",
        )
        assertTrue(
            scroll.preferredSize.height <= UIScale.scale(34) * 12,
            "the list is a window onto the commands, not the whole of them (${scroll.preferredSize.height})",
        )
    }

    @Test
    fun `the list height does not follow the filter`() {
        val page = page()
        val before = page.list.preferredSize.height

        onEdt {
            page.panel.filterButtonForTest(localizer.getString("settings_hotkeys.filter_conflicts")).doClick()
        }

        assertEquals(
            before,
            page.list.preferredSize.height,
            "narrowing the list to a handful of commands must not shrink the page under the pointer",
        )
    }

    @Test
    fun `the bounded height follows the display scale`() {
        val normal = page().list.preferredSize.height
        UIScale.setZoomFactor(2f)
        val doubled = page().list.preferredSize.height

        assertTrue(
            doubled >= UIScale.scale(34) * 11,
            "at 200% the viewport still holds eleven scaled rows ($doubled)",
        )
        assertTrue(
            doubled > normal * 1.5,
            "and it grows with the display rather than staying a fixed pixel block ($normal -> $doubled)",
        )
    }

    @Test
    fun `the page still reports a height it can scroll`() {
        val page = page()
        onEdt { page.panel.setSize(page.panel.preferredSize) }
        assertTrue(
            page.panel.preferredSize.height > 0,
            "the page is laid out from real content, not collapsed",
        )
    }

    // -------------------------------------------------------------------------
    // Scrolling
    // -------------------------------------------------------------------------

    @Test
    fun `a scroll bar reports no room at either end`() {
        assertFalse(scrollBarCanMove(scrollBar(0, 1000, 300, 0), -1), "at the top, up belongs to the page")
        assertTrue(scrollBarCanMove(scrollBar(0, 1000, 300, 0), 1), "at the top, the list can still go down")
        assertTrue(scrollBarCanMove(scrollBar(0, 1000, 300, 700), -1), "at the bottom, the list can go up")
        assertFalse(scrollBarCanMove(scrollBar(0, 1000, 300, 700), 1), "at the bottom, down belongs to the page")
        assertFalse(scrollBarCanMove(scrollBar(0, 300, 300, 0), 1), "a list with nothing to scroll has no room")
        assertFalse(scrollBarCanMove(scrollBar(0, 1000, 300, 400), 0), "a zero rotation goes nowhere")
    }

    @Test
    fun `a wheel the list can use stays with the list`() {
        val page = page()
        val forwarded = mutableListOf<MouseWheelEvent>()
        onEdt {
            page.outer.addMouseWheelListener { forwarded += it }
            page.list.verticalScrollBar.apply {
                minimum = 0
                maximum = 2000
                visibleAmount = 400
                value = 200
            }
        }

        onEdt { wheel(page.table, rotation = 1) }

        assertTrue(
            forwarded.isEmpty(),
            "the list has room in that direction, so it keeps the gesture and the page does not move",
        )
    }

    @Test
    fun `the wheel leaves the list at its top`() {
        val page = page()
        val forwarded = mutableListOf<MouseWheelEvent>()
        onEdt {
            page.outer.addMouseWheelListener { forwarded += it }
            page.list.verticalScrollBar.apply {
                minimum = 0
                maximum = 2000
                visibleAmount = 400
                value = 0
            }
        }

        onEdt { wheel(page.table, rotation = -1) }

        assertEquals(1, forwarded.size, "the page receives the gesture the list cannot use")
        assertEquals(-1, forwarded.single().wheelRotation, "and it is the same gesture, same direction")
        assertEquals(0, page.list.verticalScrollBar.value, "the list does not move as well")
    }

    @Test
    fun `the wheel leaves the list at its bottom`() {
        val page = page()
        val forwarded = mutableListOf<MouseWheelEvent>()
        onEdt {
            page.outer.addMouseWheelListener { forwarded += it }
            page.list.verticalScrollBar.apply {
                minimum = 0
                maximum = 2000
                visibleAmount = 400
                value = 1600
            }
        }

        onEdt { wheel(page.table, rotation = 1) }

        assertEquals(1, forwarded.size, "the page receives the gesture the list cannot use")
        assertEquals(1, forwarded.single().wheelRotation, "and it is the same gesture, same direction")
    }

    @Test
    fun `the page itself scrolls from a wheel over the list`() {
        val page = page()
        onEdt {
            page.list.verticalScrollBar.apply {
                minimum = 0
                maximum = 2000
                visibleAmount = 400
                value = 0
            }
        }

        onEdt { wheel(page.table, rotation = -1) }

        val after = page.outer.verticalScrollBar.value
        assertTrue(
            after < 400,
            "the forwarded gesture is one the page acts on, not just a message ($after)",
        )
    }

    @Test
    fun `a wheel over the list scroll bar also reaches the page`() {
        val page = page()
        val forwarded = mutableListOf<MouseWheelEvent>()
        onEdt {
            page.outer.addMouseWheelListener { forwarded += it }
            page.list.verticalScrollBar.apply {
                minimum = 0
                maximum = 2000
                visibleAmount = 400
                value = 1600
            }
        }

        onEdt { wheel(page.list.verticalScrollBar, rotation = 1) }

        assertEquals(1, forwarded.size, "the scroll bar does not swallow a gesture it cannot use either")
    }

    // -------------------------------------------------------------------------
    // Row actions
    // -------------------------------------------------------------------------

    @Test
    fun `every command row carries both actions at its trailing edge`() {
        val page = page()
        val bounds = page.panel.rowActionBoundsForTest(columnWidth = 100, height = 34, leftToRight = true)

        assertEquals(
            listOf(CommandRowAction.EDIT, CommandRowAction.CLEAR),
            bounds.map { it.first },
            "a row offers exactly two actions, edit first",
        )
        bounds.forEach { (_, rect) ->
            assertTrue(rect.x >= 0 && rect.x + rect.width <= 100, "the action sits inside its column: $rect")
            assertTrue(rect.width > 0 && rect.height > 0, "and is big enough to hit: $rect")
        }
        assertTrue(
            bounds[0].second.x < bounds[1].second.x,
            "left to right, edit comes before clear",
        )
    }

    @Test
    fun `the actions mirror in a right-to-left layout`() {
        val page = page()
        val ltr = page.panel.rowActionBoundsForTest(columnWidth = 100, height = 34, leftToRight = true)
        val rtl = page.panel.rowActionBoundsForTest(columnWidth = 100, height = 34, leftToRight = false)

        assertTrue(
            rtl[0].second.x > rtl[1].second.x,
            "right to left the leading edge is the right, so edit is the outer square and clear trails it",
        )
        assertEquals(ltr[0].second.width, rtl[0].second.width, "the squares are the same size either way")
        assertEquals(
            ltr[0].second.x,
            100 - (rtl[0].second.x + rtl[0].second.width),
            "and the pair is mirrored, the same distance in from the edge",
        )
    }

    @Test
    fun `the row cells follow the dialog's reading direction`() {
        val page = page()
        onEdt {
            page.panel.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT)
            val row = rowOf(page.table, HotkeyAction.TRANSLATE)
            // A renderer component is not in the hierarchy, so nothing hands it the dialog's
            // direction: if the cell does not set its own, its indentation, chips and action
            // squares stay laid out left to right inside a right-to-left table.
            listOf(COL_ACTION, COL_HOTKEY, COL_ACTIONS).forEach { column ->
                val cell = page.table.prepareRenderer(page.table.getCellRenderer(row, column), row, column)
                assertFalse(
                    cell.componentOrientation.isLeftToRight,
                    "column $column renders in the dialog's direction",
                )
            }
        }
    }

    @Test
    fun `clicking the edit action records a shortcut for that row's command`() {
        val recorded = mutableListOf<HotkeyAction>()
        val page = page(recorder = { action, _ -> recorded += action; null })
        val row = rowOf(page.table, HotkeyAction.TRANSLATE)

        clickRowAction(page, row, CommandRowAction.EDIT)

        assertEquals(listOf(HotkeyAction.TRANSLATE), recorded, "the row decides which command is edited")
    }

    @Test
    fun `clicking the clear action clears that command and nothing else`() {
        val page = page()
        val before = page.settings.state.value.workingConfiguration.hotkeys
        val row = rowOf(page.table, HotkeyAction.SWAP_LANGUAGES)

        clickRowAction(page, row, CommandRowAction.CLEAR)

        val after = page.settings.state.value.workingConfiguration.hotkeys
        val cleared = after.first { it.action == HotkeyAction.SWAP_LANGUAGES }
        assertFalse(cleared.hasBinding, "the shortcut of the clicked row is cleared")
        assertEquals(
            HotkeyScope.LOCAL,
            cleared.scope,
            "clearing from the row keeps the scope, exactly as the button under the list did",
        )
        assertEquals(
            before.filterNot { it.action == HotkeyAction.SWAP_LANGUAGES },
            after.filterNot { it.action == HotkeyAction.SWAP_LANGUAGES },
            "no other command is touched",
        )
    }

    @Test
    fun `a row's actions follow the global switch, like the buttons do`() {
        val recorded = mutableListOf<HotkeyAction>()
        val page = page(
            configuration = Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false),
            recorder = { action, _ -> recorded += action; null },
        )
        val row = rowOf(page.table, HotkeyAction.SHOW_QUICK_TRANSLATE)

        clickRowAction(page, row, CommandRowAction.EDIT)
        assertTrue(
            recorded.isEmpty(),
            "a global shortcut is not editable through its row while the registration is off",
        )

        val clearRow = rowOf(page.table, HotkeyAction.SHOW_QUICK_TRANSLATE)
        clickRowAction(page, clearRow, CommandRowAction.CLEAR)
        assertTrue(
            page.settings.state.value.workingConfiguration.hotkeys
                .first { it.action == HotkeyAction.SHOW_QUICK_TRANSLATE }.hasBinding,
            "and it cannot be cleared from the row either",
        )
    }

    @Test
    fun `clicking a category heading's action cell does nothing`() {
        val page = page()
        val headingRow = (0 until page.table.rowCount)
            .first { page.table.getValueAt(it, COL_HOTKEY) == null }

        clickRowAction(page, headingRow, CommandRowAction.CLEAR)

        assertEquals(-1, page.table.selectedRow, "a heading is not a selectable command")
    }

    @Test
    fun `enter on a selected row opens the recorder`() {
        val recorded = mutableListOf<HotkeyAction>()
        val page = page(recorder = { action, _ -> recorded += action; null })

        onEdt {
            val row = rowOf(page.table, HotkeyAction.CLEAR_INPUT)
            page.table.setRowSelectionInterval(row, row)
            page.table.actionMap.get("editSelectedCommand")?.actionPerformed(null)
        }

        assertEquals(listOf(HotkeyAction.CLEAR_INPUT), recorded, "the keyboard path reaches the same editor")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun wheel(target: Component, rotation: Int) {
        target.dispatchEvent(
            MouseWheelEvent(
                target,
                MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                0,
                target.width / 2,
                target.height / 2,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                3,
                rotation,
            )
        )
    }

    private fun rowOf(table: JTable, action: HotkeyAction): Int =
        (0 until table.rowCount).firstOrNull { row ->
            (table.getValueAt(row, COL_HOTKEY) as? HotkeyBinding)?.action == action
        } ?: error("no row for $action")

    /**
     * Clicks one of [row]'s action squares.
     *
     * The column widths are set by hand because the table is not on screen: without them the
     * column under the pointer cannot be resolved and the click lands nowhere. The point is taken
     * from the same geometry the renderer uses, so the test clicks where the square is drawn.
     */
    private fun clickRowAction(page: Page, row: Int, action: CommandRowAction) = onEdt {
        val table = page.table
        (0 until table.columnCount).forEach { index -> table.columnModel.getColumn(index).width = 100 }
        table.columnModel.getColumn(COL_ACTIONS).width = 78
        val cell = table.getCellRect(row, COL_ACTIONS, true)
        val rect = page.panel.rowActionBoundsForTest(cell.width, cell.height, leftToRight = true)
            .first { it.first == action }.second
        table.dispatchEvent(
            MouseEvent(
                table,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                cell.x + rect.x + rect.width / 2,
                cell.y + rect.y + rect.height / 2,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        )
    }

    private companion object {
        const val COL_ACTION = 0
        const val COL_HOTKEY = 1
        const val COL_ACTIONS = 3
    }
}
