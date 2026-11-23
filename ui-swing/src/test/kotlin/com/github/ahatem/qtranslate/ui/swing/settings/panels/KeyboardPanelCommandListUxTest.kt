package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.util.UIScale
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import java.awt.Component
import java.awt.Dimension
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.accessibility.Accessible
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleState
import javax.swing.JScrollBar
import javax.swing.JScrollPane
import javax.swing.JTable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The command list is a scroll pane inside the settings page's scroll pane. A wheel gesture must
 * leave the list when it has nothing left to scroll (Swing's scroll pane consumes every wheel
 * event on its own), and a shortcut must be changeable and clearable from its own row.
 */
class KeyboardPanelCommandListUxTest {
    private val fixture = SettingsPanelFixture()
    private val localizer get() = fixture.localizer

    @AfterTest
    fun tearDown() = fixture.close()

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
    ): Page {
        val settings = fixture.store(configuration)
        val panel = onEdt { KeyboardPanel(settings, localizer, recorder = recorder) }
        onEdt { panel.render(settings.state.value) }
        val outer = onEdt {
            JScrollPane(panel).apply {
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
        onEdt { outer.updateUI() }
        onEdt { layOut(outer, panel) }
        return Page(settings, panel, outer)
    }

    /** Offscreen components stay 0x0, so the sizes a visible window would give them are set by hand. */
    private fun layOut(outer: JScrollPane, panel: KeyboardPanel) {
        outer.viewport.size = outer.size
        panel.setSize(outer.viewport.extentSize)
        panel.doLayout()
        val list = panel.listScrollForTest()
        list.setSize(list.preferredSize)
        list.doLayout()
        list.viewport.size = Dimension(
            list.width - list.verticalScrollBar.preferredSize.width,
            list.height,
        )
        val view = list.viewport.view
        view.setSize(
            view.preferredSize.width.coerceAtLeast(list.viewport.width),
            view.preferredSize.height,
        )
        list.viewport.doLayout()
    }

    private fun scrollBar(min: Int, max: Int, visible: Int, value: Int): JScrollBar =
        JScrollBar().apply {
            minimum = min
            maximum = max
            visibleAmount = visible
            this.value = value
        }

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
    fun `the wheel stays with the list while it has room and passes to the page otherwise`() {
        class Case(
            val name: String,
            val listValue: (JScrollBar) -> Int,
            val target: (Page) -> Component,
            val rotation: Int,
            val listMoves: Int,
            val pageMoves: Int,
        )
        val cases = listOf(
            Case("room to scroll down", { 200 }, { it.list.viewport.view }, 1, listMoves = 1, pageMoves = 0),
            Case("at the top, up", { it.minimum }, { it.list.viewport.view }, -1, listMoves = 0, pageMoves = -1),
            Case("at the bottom, down", { it.maximum - it.visibleAmount }, { it.list.viewport.view }, 1, listMoves = 0, pageMoves = 1),
            Case("at the top, up, over the table", { it.minimum }, { it.table }, -1, listMoves = 0, pageMoves = -1),
            Case("at the bottom, down, over the scroll bar", { it.maximum - it.visibleAmount }, { it.list.verticalScrollBar }, 1, listMoves = 0, pageMoves = 1),
        )
        for (case in cases) {
            val page = page()
            onEdt {
                page.list.verticalScrollBar.apply {
                    minimum = 0
                    maximum = 2000
                    visibleAmount = 400
                    value = case.listValue(this)
                }
            }
            val pageBefore = onEdt { page.outer.verticalScrollBar.value }
            val listBefore = onEdt { page.list.verticalScrollBar.value }

            onEdt { wheel(case.target(page), case.rotation) }

            val pageDelta = onEdt { page.outer.verticalScrollBar.value } - pageBefore
            val listDelta = onEdt { page.list.verticalScrollBar.value } - listBefore
            assertEquals(case.pageMoves, pageDelta.coerceIn(-1, 1), "page movement, ${case.name}")
            assertEquals(case.listMoves, listDelta.coerceIn(-1, 1), "list movement, ${case.name}")
        }
    }

    @Test
    fun `the actions column and its cells are named for the accessibility tree`() {
        val page = page()
        assertEquals(
            localizer.getString("settings_hotkeys.column_actions"),
            onEdt { page.table.getColumnName(COL_ACTIONS) },
        )
        assertEquals(
            localizer.getString("settings_hotkeys.command_list_accessible_name"),
            onEdt { page.table.accessibleContext.accessibleName },
        )

        val context = actionsCell(page, HotkeyAction.TRANSLATE).accessibleContext
        assertEquals(localizer.getString("settings_hotkeys.row_actions_accessible_name"), context.accessibleName)
        assertTrue(context.accessibleDescription.contains(localizer.getString("settings_hotkeys.action_translate")))
        assertEquals(2, context.accessibleChildrenCount, "one child per action")
        assertEquals(
            localizer.getString("settings_hotkeys.row_edit_tooltip"),
            context.getAccessibleChild(0)!!.accessibleContext.accessibleName,
        )
        assertEquals(
            localizer.getString("settings_hotkeys.row_clear_tooltip"),
            context.getAccessibleChild(1)!!.accessibleContext.accessibleName,
        )
        assertEquals(AccessibleRole.PUSH_BUTTON, context.getAccessibleChild(0)!!.accessibleContext.accessibleRole)

        val heading: Accessible? = onEdt {
            val headingRow = (0 until page.table.rowCount).first { page.table.getValueAt(it, COL_HOTKEY) == null }
            page.table.accessibleContext?.getAccessibleChild(headingRow * page.table.columnCount + COL_ACTIONS)
        }
        assertNotNull(heading, "a heading still has a cell")
        assertEquals(0, heading.accessibleContext.accessibleChildrenCount, "but nothing to change or clear")
    }

    @Test
    fun `a shortcut can be changed and cleared through the accessibility tree`() {
        val recorded = mutableListOf<HotkeyAction>()
        val page = page(recorder = { action, _ -> recorded += action; null })

        assertTrue(
            actionButton(page, HotkeyAction.TRANSLATE, CommandRowAction.EDIT)
                .accessibleContext.accessibleAction.doAccessibleAction(0),
            "the action reports that it ran",
        )
        assertEquals(listOf(HotkeyAction.TRANSLATE), recorded, "and it edits the command it belongs to")

        actionButton(page, HotkeyAction.SWAP_LANGUAGES, CommandRowAction.CLEAR)
            .accessibleContext.accessibleAction.doAccessibleAction(0)
        assertFalse(
            page.settings.state.value.workingConfiguration.hotkeys
                .first { it.action == HotkeyAction.SWAP_LANGUAGES }.hasBinding,
            "the clear action clears its own command",
        )
    }

    @Test
    fun `the actions are not offered while the command cannot be edited`() {
        val page = page(configuration = Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
        val button = actionButton(page, HotkeyAction.SHOW_QUICK_TRANSLATE, CommandRowAction.EDIT)

        assertFalse(
            button.accessibleContext.accessibleStateSet.contains(AccessibleState.ENABLED),
            "a global shortcut is announced as unavailable while its registration is off",
        )
        assertFalse(
            button.accessibleContext.accessibleAction.doAccessibleAction(0),
            "and the action declines rather than doing nothing silently",
        )
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

    /** The accessibility tree's view of [action]'s row actions. */
    private fun actionsCell(page: Page, action: HotkeyAction): Accessible = onEdt {
        val table = page.table
        val row = rowOf(table, action)
        table.accessibleContext?.getAccessibleChild(row * table.columnCount + COL_ACTIONS)
            ?: error("no accessible cell for $action")
    }

    /** One of the two buttons the accessibility tree offers for [action]. */
    private fun actionButton(page: Page, action: HotkeyAction, kind: CommandRowAction): Accessible {
        val cell = actionsCell(page, action)
        val index = if (kind == CommandRowAction.EDIT) 0 else 1
        return cell.accessibleContext?.getAccessibleChild(index)
            ?: error("$action has no $kind action")
    }

    private fun rowOf(table: JTable, action: HotkeyAction): Int =
        (0 until table.rowCount).firstOrNull { row ->
            (table.getValueAt(row, COL_HOTKEY) as? HotkeyBinding)?.action == action
        } ?: error("no row for $action")

    /** Clicks one of [row]'s action squares, at the point the renderer's own geometry puts it. */
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
        const val COL_HOTKEY = 1
        const val COL_ACTIONS = 3
    }
}
