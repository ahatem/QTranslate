package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import java.awt.Container
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JTable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The global switch only turns off the OS-level registration GLOBAL bindings rely on: LOCAL rows
 * stay editable, GLOBAL rows do not, and neither loses its shortcut when the switch is toggled.
 */
class KeyboardPanelGlobalHotkeysTest {
    private val fixture = SettingsPanelFixture()
    private val localizer get() = fixture.localizer

    @AfterTest
    fun tearDown() = fixture.close()

    private fun store(configuration: Configuration): SettingsStore = fixture.store(configuration)

    private fun panel(
        store: SettingsStore,
        recorder: ((HotkeyAction, HotkeyBinding?) -> HotkeyBinding?)? = null,
    ): KeyboardPanel {
        val panel = onEdt { KeyboardPanel(store, localizer, recorder = recorder) }
        onEdt { panel.render(store.state.value) }
        return panel
    }

    /** The Edit and Clear buttons of the assignments table; "Show Main Window" has its own Clear. */
    private fun editAndClear(panel: KeyboardPanel): Pair<JButton, JButton> {
        val edit = descendants(panel).filterIsInstance<JButton>()
            .single { it.text == localizer.getString("settings_hotkeys.edit_button") }
        val clear = (edit.parent as Container).components.filterIsInstance<JButton>()
            .single { it.text == localizer.getString("settings_hotkeys.clear_button") }
        return edit to clear
    }

    /** The "Enable global hotkeys" switch at the top of the panel. */
    private fun globalSwitch(panel: KeyboardPanel): JCheckBox =
        descendants(panel).filterIsInstance<JCheckBox>()
            .single { it.text == localizer.getString("settings_hotkeys.enable_global") }

    /** The Change and Clear buttons of the "Show Main Window" row, which is always global. */
    private fun showMainWindowButtons(panel: KeyboardPanel): Pair<JButton, JButton> {
        val change = descendants(panel).filterIsInstance<JButton>()
            .single { it.text == localizer.getString("settings_hotkeys.change_button") }
        val clear = descendants(panel).filterIsInstance<JButton>()
            .filter { it.text == localizer.getString("settings_hotkeys.clear_button") }
            .single { it !== editAndClear(panel).second }
        return change to clear
    }

    /** The "Double Ctrl" option, which needs the raw keyboard capture of the global backend. */
    private fun doubleCtrlOption(panel: KeyboardPanel): JCheckBox =
        descendants(panel).filterIsInstance<JCheckBox>()
            .single { it.text == localizer.getString("settings_hotkeys.double_ctrl_label") }

    /** The row that carries [action], found through the binding the row displays. */
    private fun rowOf(table: JTable, action: HotkeyAction): Int =
        (0 until table.rowCount).firstOrNull { row ->
            (table.getValueAt(row, COL_HOTKEY) as? HotkeyBinding)?.action == action
        } ?: error("no row for $action")

    private fun select(table: JTable, action: HotkeyAction) {
        val row = rowOf(table, action)
        onEdt { table.setRowSelectionInterval(row, row) }
    }

    /** The scope every listed action carries, read from the binding each row displays. */
    private fun scopesByAction(table: JTable): Map<HotkeyAction, HotkeyScope> =
        (0 until table.rowCount).mapNotNull { row ->
            val binding = table.getValueAt(row, COL_HOTKEY) as? HotkeyBinding
            binding?.let { it.action to it.scope }
        }.toMap()

    @Test
    fun `the show main window row follows the global switch`() {
        // (global switch on) to (change, clear, double ctrl) enabled
        val cases = listOf(false to Triple(false, false, false), true to Triple(true, false, true))
        for ((globalOn, expected) in cases) {
            val panel = panel(store(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = globalOn)))
            val (change, clear) = showMainWindowButtons(panel)

            assertEquals(
                expected,
                Triple(change.isEnabled, clear.isEnabled, doubleCtrlOption(panel).isEnabled),
                "change/clear/double ctrl with global hotkeys on=$globalOn",
            )
        }
    }

    @Test
    fun `editing and double-clicking depend on the global switch and the row's scope`() {
        // (global switch on, row scope) to (edit and clear enabled, double-click opens the recorder)
        val cases = listOf(
            (false to HotkeyScope.LOCAL) to (true to true),
            (false to HotkeyScope.GLOBAL) to (false to false),
            (true to HotkeyScope.LOCAL) to (true to true),
            (true to HotkeyScope.GLOBAL) to (true to true),
        )
        for ((input, expected) in cases) {
            val (globalOn, scope) = input
            val recorded = mutableListOf<HotkeyAction>()
            val panel = panel(
                store(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = globalOn)),
                recorder = { action, _ -> recorded += action; null },
            )
            val table = panel.tableForTest()
            val (edit, clear) = editAndClear(panel)
            val action = scopesByAction(table).entries.first { it.value == scope }.key
            val label = "global on=$globalOn, $scope row"

            select(table, action)
            assertEquals(action, table.selectedAction(), "$label stays selectable")
            assertEquals(
                localizer.getString(if (scope == HotkeyScope.LOCAL) "settings_hotkeys.scope_local" else "settings_hotkeys.scope_global"),
                table.scopeLabel(action),
                "$label shows its scope",
            )
            assertEquals(expected.first, edit.isEnabled, "edit for $label")
            assertEquals(expected.first, clear.isEnabled, "clear for $label")

            doubleClickHotkeyCell(table, rowOf(table, action))
            assertEquals(if (expected.second) listOf(action) else emptyList(), recorded, "double-click for $label")
            assertTrue(table.isEnabled, "the table holds local shortcuts too, so it never greys out ($label)")
        }

        val on = panel(store(Configuration.DEFAULT))
        val (edit, clear) = editAndClear(on)
        HotkeyAction.entries.filter { it != HotkeyAction.SHOW_MAIN_WINDOW }.forEach { action ->
            select(on.tableForTest(), action)
            assertTrue(edit.isEnabled && clear.isEnabled, "$action is editable while global hotkeys are on")
        }
    }

    @Test
    fun `turning global hotkeys off and on again keeps the configured shortcuts`() {
        val settings = store(Configuration.DEFAULT)
        val panel = panel(settings)
        val table = panel.tableForTest()
        val before = settings.state.value.workingConfiguration.hotkeys
        val rowsBefore = table.bindings()

        onEdt { globalSwitch(panel).doClick() }
        onEdt { panel.render(settings.state.value) }

        assertFalse(settings.state.value.workingConfiguration.isGlobalHotkeysEnabled, "the switch is off")
        assertEquals(before, settings.state.value.workingConfiguration.hotkeys, "switching off keeps the bindings")
        assertEquals(rowsBefore, table.bindings(), "switching off keeps the rows as they were")

        onEdt { globalSwitch(panel).doClick() }
        onEdt { panel.render(settings.state.value) }

        assertTrue(settings.state.value.workingConfiguration.isGlobalHotkeysEnabled, "the switch is on again")
        assertEquals(before, settings.state.value.workingConfiguration.hotkeys, "switching on keeps the bindings")
        assertEquals(rowsBefore, table.bindings(), "switching on keeps the rows as they were")
    }

    /** The binding each row displays, which render() fills from the configuration for that row. */
    private fun JTable.bindings(): List<HotkeyBinding?> =
        (0 until rowCount).map { getValueAt(it, COL_HOTKEY) as? HotkeyBinding }

    /** The scope the row of [action] shows, as the panel renders it. */
    private fun JTable.scopeLabel(action: HotkeyAction): String =
        scopeLabelAt(this, rowOf(this, action))

    /** The scope the row at [row] shows, as the panel renders it. */
    private fun scopeLabelAt(table: JTable, row: Int): String =
        table.getValueAt(row, COL_SCOPE).toString()

    /** The row carrying [label] in the action column; a row with no binding cannot be found by binding. */
    private fun rowNamed(table: JTable, label: String): Int =
        (0 until table.rowCount).firstOrNull { table.getValueAt(it, COL_ACTION)?.toString() == label }
            ?: error("no row labelled $label")

    /** The action of the selected row, which the panel reads when a row is picked. */
    private fun JTable.selectedAction(): HotkeyAction? =
        (getValueAt(selectedRow, COL_HOTKEY) as? HotkeyBinding)?.action

    private fun doubleClickHotkeyCell(table: JTable, row: Int) = clickCell(table, row, COL_HOTKEY, 2)

    /** Clicks a cell. Column widths are set by hand because the table is not on screen. */
    private fun clickCell(table: JTable, row: Int, column: Int, clickCount: Int = 1) = onEdt {
        (0 until table.columnCount).forEach { table.columnModel.getColumn(it).width = 100 }
        val cell = table.getCellRect(row, column, true)
        table.dispatchEvent(
            MouseEvent(
                table,
                MouseEvent.MOUSE_CLICKED,
                0L,
                0,
                cell.x + cell.width / 2,
                cell.y + cell.height / 2,
                clickCount,
                false,
                MouseEvent.BUTTON1
            )
        )
    }

    /** The scope the working configuration holds for [action]. */
    private fun scopeInDraft(store: SettingsStore, action: HotkeyAction): HotkeyScope? =
        store.state.value.workingConfiguration.hotkeys.find { it.action == action }?.scope

    @Test
    fun `the scope cell turns a shortcut into the other scope in both directions`() {
        for ((from, to) in listOf(HotkeyScope.GLOBAL to HotkeyScope.LOCAL, HotkeyScope.LOCAL to HotkeyScope.GLOBAL)) {
            val settings = store(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
            val panel = panel(settings)
            val table = panel.tableForTest()

            val action = scopesByAction(table).entries.first { it.value == from }.key
            clickCell(table, rowOf(table, action), COL_SCOPE)
            onEdt { panel.render(settings.state.value) }

            assertEquals(to, scopeInDraft(settings, action), "$from becomes $to")
            if (to == HotkeyScope.LOCAL) {
                assertEquals(localizer.getString("settings_hotkeys.scope_local"), table.scopeLabel(action))
            }
        }
    }

    @Test
    fun `a row without a binding counts as global`() {
        val settings = store(Configuration.DEFAULT)
        settings.dispatch(
            SettingsIntent.UpdateDraft(
                settings.state.value.workingConfiguration.copy(
                    isGlobalHotkeysEnabled = false,
                    hotkeys = settings.state.value.workingConfiguration.hotkeys
                        .filterNot { it.action == HotkeyAction.TRANSLATE }
                )
            )
        )
        val panel = panel(settings)
        val table = panel.tableForTest()
        val (edit, clear) = editAndClear(panel)

        val row = rowNamed(table, localizer.getString("settings_hotkeys.action_translate"))
        assertEquals(
            localizer.getString("settings_hotkeys.scope_global"),
            scopeLabelAt(table, row),
            "a row that carries no binding reads as global"
        )
        onEdt { table.setRowSelectionInterval(row, row) }
        assertFalse(edit.isEnabled, "a row that carries no binding has no local scope to fall back on")
        assertFalse(clear.isEnabled, "a row that carries no binding has no local scope to fall back on")
    }

    @Test
    fun `clearing a local row keeps it local`() {
        val settings = store(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
        val panel = panel(settings)
        val table = panel.tableForTest()
        val (_, clear) = editAndClear(panel)

        val local = scopesByAction(table).entries.first { it.value == HotkeyScope.LOCAL }
        select(table, local.key)
        onEdt { clear.doClick() }

        val binding = settings.state.value.workingConfiguration.hotkeys.first { it.action == local.key }
        assertFalse(binding.hasBinding, "the shortcut is cleared")
        assertEquals(HotkeyScope.LOCAL, binding.scope, "clearing must not promote a local shortcut to a global one")
    }

    @Test
    fun `editing a local row keeps it local`() {
        val settings = store(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
        val panel = panel(
            settings,
            recorder = { action, current ->
                HotkeyRecorderDialog.recordedBinding(action, current, KeyEvent.VK_F5, 0)
            }
        )
        val table = panel.tableForTest()
        val (edit, _) = editAndClear(panel)

        val local = scopesByAction(table).entries.first { it.value == HotkeyScope.LOCAL }
        select(table, local.key)
        onEdt { edit.doClick() }

        val binding = settings.state.value.workingConfiguration.hotkeys.first { it.action == local.key }
        assertEquals(KeyEvent.VK_F5, binding.keyCode, "the recorded shortcut is saved")
        assertEquals(HotkeyScope.LOCAL, binding.scope, "recording a key must not promote a local shortcut")
    }

    private companion object {
        const val COL_ACTION = 0
        const val COL_HOTKEY = 1
        const val COL_SCOPE = 2
    }
}
