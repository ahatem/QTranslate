package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JTable
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A shortcut's scope decides what it needs, and the global switch only turns off the OS-level
 * registration that GLOBAL bindings rely on. LOCAL bindings are installed on QTranslate's own
 * InputMap, so they keep working — and keep being configurable — while global hotkeys are off.
 *
 * The panel used to grey out the whole assignments table and the Edit and Clear buttons with the
 * global switch, which left a user who never wanted system-wide shortcuts unable to change their
 * local ones. These tests hold the scope boundary in place: LOCAL rows stay editable, GLOBAL rows
 * do not, and neither loses its configured shortcut when the switch is toggled.
 */
class KeyboardPanelGlobalHotkeysTest {
    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-kbd-global").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun store(configuration: Configuration): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-kbd-global-store").toFile()
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

    private fun panel(
        store: SettingsStore,
        recorder: ((HotkeyAction, HotkeyBinding?) -> HotkeyBinding?)? = null,
    ): KeyboardPanel {
        val panel = onEdt { KeyboardPanel(store, localizer, recorder = recorder) }
        onEdt { panel.render(store.state.value) }
        return panel
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    /**
     * The Edit and Clear buttons of the assignments table.
     *
     * Found through the component tree rather than through a test hook, so the same test can be
     * run against the panel as it was before the fix. The Clear button of the "Show Main Window"
     * row carries the same label, so the table's own Clear is the one that sits beside Edit.
     */
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

    /**
     * The Change and Clear buttons of the "Show Main Window" row.
     *
     * That action is always global — it is the one shortcut whose scope cannot be toggled — so
     * these buttons follow the global switch rather than the scope of a row.
     */
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
    fun `the show main window row stays disabled while global hotkeys are off`() {
        val settings = store(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
        val panel = panel(settings)
        val (change, clear) = showMainWindowButtons(panel)

        assertFalse(change.isEnabled, "showing the main window is a global shortcut")
        assertFalse(clear.isEnabled, "showing the main window is a global shortcut")
        assertFalse(doubleCtrlOption(panel).isEnabled, "Double Ctrl is read from the global keyboard")
    }

    @Test
    fun `the show main window row is editable while global hotkeys are on`() {
        val settings = store(Configuration.DEFAULT)
        val panel = panel(settings)
        val (change, clear) = showMainWindowButtons(panel)

        assertTrue(change.isEnabled, "the shortcut of the main window can be changed")
        assertFalse(clear.isEnabled, "the main window carries no key to clear — it is the Double Ctrl gesture")
        assertTrue(doubleCtrlOption(panel).isEnabled, "Double Ctrl can be turned on")
    }

    @Test
    fun `the assignments table stays usable when global hotkeys are off`() {
        val settings = store(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
        val table = panel(settings).tableForTest()

        assertTrue(table.isEnabled, "the table holds the local shortcuts too, so it must not grey out")
    }

    @Test
    fun `a local row stays editable while global hotkeys are off`() {
        val settings = store(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
        val panel = panel(settings)
        val table = panel.tableForTest()
        val (edit, clear) = editAndClear(panel)

        val local = scopesByAction(table).entries.first { it.value == HotkeyScope.LOCAL }
        select(table, local.key)

        assertEquals(local.key, table.selectedAction(), "the local row stays selectable")
        assertEquals(
            localizer.getString("settings_hotkeys.scope_local"),
            table.scopeLabel(local.key),
            "the row says which scope it is, so a mixed table reads"
        )
        assertTrue(edit.isEnabled, "a local shortcut works without global registration, so it stays editable")
        assertTrue(clear.isEnabled, "a local shortcut can still be cleared")
    }

    @Test
    fun `a global row is not editable while global hotkeys are off`() {
        val settings = store(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
        val panel = panel(settings)
        val table = panel.tableForTest()
        val (edit, clear) = editAndClear(panel)

        val global = scopesByAction(table).entries.first { it.value == HotkeyScope.GLOBAL }
        select(table, global.key)

        assertEquals(
            localizer.getString("settings_hotkeys.scope_global"),
            table.scopeLabel(global.key),
            "the row says which scope it is, so a mixed table reads"
        )
        assertFalse(edit.isEnabled, "a global shortcut needs the registration that is switched off")
        assertFalse(clear.isEnabled, "a global shortcut needs the registration that is switched off")
    }

    @Test
    fun `every row stays editable while global hotkeys are on`() {
        val settings = store(Configuration.DEFAULT)
        val panel = panel(settings)
        val table = panel.tableForTest()
        val (edit, clear) = editAndClear(panel)

        HotkeyAction.entries.filter { it != HotkeyAction.SHOW_MAIN_WINDOW }.forEach { action ->
            select(table, action)
            assertTrue(edit.isEnabled, "$action is editable while global hotkeys are on")
            assertTrue(clear.isEnabled, "$action can be cleared while global hotkeys are on")
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

    /**
     * The row carrying [label] in the action column.
     *
     * Used for a row that carries no binding, which therefore cannot be found through the
     * binding it shows.
     */
    private fun rowNamed(table: JTable, label: String): Int =
        (0 until table.rowCount).firstOrNull { table.getValueAt(it, COL_ACTION)?.toString() == label }
            ?: error("no row labelled $label")

    /** The action of the selected row, which the panel reads when a row is picked. */
    private fun JTable.selectedAction(): HotkeyAction? =
        (getValueAt(selectedRow, COL_HOTKEY) as? HotkeyBinding)?.action

    /**
     * Double-clicks the hotkey cell of [row], the way the panel reads a double-click.
     *
     * The column widths are set by hand because the table is not on screen: without them the
     * column under the pointer cannot be resolved and the click lands nowhere.
     */
    private fun doubleClickHotkeyCell(table: JTable, row: Int) = clickCell(table, row, COL_HOTKEY, 2)

    /**
     * Clicks the cell of [row] in [column], the way the panel reads a click.
     *
     * The column widths are set by hand because the table is not on screen: without them the
     * column under the pointer cannot be resolved and the click lands nowhere.
     */
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
    fun `the scope cell turns a global shortcut into a local one while global hotkeys are off`() {
        val settings = store(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
        val panel = panel(settings)
        val table = panel.tableForTest()

        val global = scopesByAction(table).entries.first { it.value == HotkeyScope.GLOBAL }
        clickCell(table, rowOf(table, global.key), COL_SCOPE)
        onEdt { panel.render(settings.state.value) }

        assertEquals(
            HotkeyScope.LOCAL,
            scopeInDraft(settings, global.key),
            "the scope cell is the way out for a global shortcut that cannot be edited"
        )
        assertEquals(
            localizer.getString("settings_hotkeys.scope_local"),
            table.scopeLabel(global.key),
            "the row shows the scope it now has"
        )
    }

    @Test
    fun `the scope cell turns a local shortcut into a global one`() {
        val settings = store(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
        val panel = panel(settings)
        val table = panel.tableForTest()

        val local = scopesByAction(table).entries.first { it.value == HotkeyScope.LOCAL }
        clickCell(table, rowOf(table, local.key), COL_SCOPE)
        onEdt { panel.render(settings.state.value) }

        assertEquals(
            HotkeyScope.GLOBAL,
            scopeInDraft(settings, local.key),
            "the scope cell works in both directions — it is how a shortcut is declared global"
        )
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
        // The recorder is replaced by the same binding it would build, so the scope it keeps is
        // the production one rather than whatever the test happens to invent.
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

    @Test
    fun `double-clicking a local row opens the recorder while global hotkeys are off`() {
        val recorded = mutableListOf<HotkeyAction>()
        val settings = store(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
        val panel = panel(settings, recorder = { action, _ -> recorded += action; null })
        val table = panel.tableForTest()

        val local = scopesByAction(table).entries.first { it.value == HotkeyScope.LOCAL }
        doubleClickHotkeyCell(table, rowOf(table, local.key))

        assertEquals(listOf(local.key), recorded, "a local shortcut is recorded without global hotkeys")
    }

    @Test
    fun `double-clicking a global row does nothing while global hotkeys are off`() {
        val recorded = mutableListOf<HotkeyAction>()
        val settings = store(Configuration.DEFAULT.copy(isGlobalHotkeysEnabled = false))
        val panel = panel(settings, recorder = { action, _ -> recorded += action; null })
        val table = panel.tableForTest()

        val global = scopesByAction(table).entries.first { it.value == HotkeyScope.GLOBAL }
        doubleClickHotkeyCell(table, rowOf(table, global.key))

        assertTrue(recorded.isEmpty(), "a global shortcut needs the registration that is switched off")
    }

    @Test
    fun `double-clicking a global row opens the recorder while global hotkeys are on`() {
        val recorded = mutableListOf<HotkeyAction>()
        val settings = store(Configuration.DEFAULT)
        val panel = panel(settings, recorder = { action, _ -> recorded += action; null })
        val table = panel.tableForTest()

        val global = scopesByAction(table).entries.first { it.value == HotkeyScope.GLOBAL }
        doubleClickHotkeyCell(table, rowOf(table, global.key))

        assertEquals(listOf(global.key), recorded, "a global shortcut is recorded while global hotkeys are on")
    }

    private companion object {
        const val COL_ACTION = 0
        const val COL_HOTKEY = 1
        const val COL_SCOPE = 2
    }}
