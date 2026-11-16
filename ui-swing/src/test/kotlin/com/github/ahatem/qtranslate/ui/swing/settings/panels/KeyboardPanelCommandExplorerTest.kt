package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.CommandRegistry
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyPresetKind
import com.github.ahatem.qtranslate.core.settings.data.HotkeyPresets
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.formatChord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The panel is the application's command reference: it has to show every registered command,
 * say what each one does, and say where the ones that read a selection get their text from.
 *
 * These assertions are about the list's content and structure rather than its pixels — the
 * registry already owns which commands exist, so the risk here is a command that exists and is
 * never described, or a description attached to the wrong command.
 */
class KeyboardPanelCommandExplorerTest {
    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-kbd-explorer").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun store(configuration: Configuration): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-kbd-explorer-store").toFile()
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

    private fun panelFor(configuration: Configuration = Configuration.DEFAULT): Pair<SettingsStore, KeyboardPanel> {
        val settings = store(configuration)
        val panel = onEdt { KeyboardPanel(settings, localizer) }
        onEdt { panel.render(settings.state.value) }
        return settings to panel
    }

    /** One row of the list: a category heading, or a command. */
    private data class Row(val heading: String?, val action: HotkeyAction?)

    private fun KeyboardPanel.rows(): List<Row> {
        val table = tableForTest()
        return (0 until table.rowCount).map { index ->
            val binding = table.getValueAt(index, COL_HOTKEY) as? HotkeyBinding
            Row(
                heading = if (binding == null) table.getValueAt(index, COL_ACTION)?.toString() else null,
                action = binding?.action,
            )
        }
    }

    private fun KeyboardPanel.listedActions(): List<HotkeyAction> = rows().mapNotNull { it.action }

    private fun LocalizationManager.categoryLabel(category: String) =
        getString("settings_hotkeys.category_$category")

    private fun KeyboardPanel.select(action: HotkeyAction) {
        val table = tableForTest()
        val row = rows().indexOfFirst { it.action == action }
        assertTrue(row >= 0, "$action is listed")
        onEdt { table.setRowSelectionInterval(row, row) }
    }

    @Test
    fun `every registered command is listed exactly once`() {
        val (_, panel) = panelFor()
        val listed = panel.listedActions()

        // Order is pinned by KeyboardPanelActionListTest; what matters here is coverage.
        assertEquals(CommandRegistry.editableActions.toSet(), listed.toSet(), "the table lists every registered command")
        // The one command with its own editing path above the table: still represented, once.
        assertEquals(
            HotkeyAction.entries.toSet(),
            listed.toSet() + HotkeyAction.SHOW_MAIN_WINDOW,
            "every command in the registry appears exactly once",
        )
        assertEquals(listed.size, listed.toSet().size, "no command is listed twice: $listed")
    }

    @Test
    fun `commands are grouped under their registry category`() {
        val (_, panel) = panelFor()
        val rows = panel.rows()

        assertEquals(
            listOf("translation", "writing", "dictionary", "ocr", "capture", "navigation", "editing", "document", "window")
                .map { localizer.categoryLabel(it) },
            rows.mapNotNull { it.heading },
            "category groups appear once each, in the panel's task order",
        )

        var current: String? = null
        rows.forEach { row ->
            if (row.heading != null) {
                current = row.heading
                return@forEach
            }
            val action = row.action ?: return@forEach
            val category = CommandRegistry.specFor(action)?.category
            assertTrue(category != null, "$action has a category")
            assertEquals(
                localizer.categoryLabel(category!!),
                current,
                "$action is filed under its own category",
            )
        }
    }

    @Test
    fun `a command inside a group keeps the registry order within that group`() {
        val (_, panel) = panelFor()
        val rows = panel.rows()

        var withinGroup = mutableListOf<HotkeyAction>()
        rows.forEachIndexed { index, row ->
            if (row.action != null) withinGroup += row.action!!
            val lastRow = index == rows.lastIndex
            if (row.heading != null || lastRow) {
                if (withinGroup.isNotEmpty()) {
                    val expected = withinGroup.sortedBy { CommandRegistry.editableActions.indexOf(it) }
                    assertEquals(expected, withinGroup, "a group lists its actions in registry order")
                    withinGroup = mutableListOf()
                }
            }
        }
    }

    @Test
    fun `every command explains itself in words`() {
        val (_, panel) = panelFor()
        val placeholder = localizer.getString("settings_hotkeys.details_placeholder")

        panel.listedActions().forEach { action ->
            panel.select(action)
            val details = panel.detailsTextForTest()
            val description = localizer.getString(CommandExplorerText.descriptionKeys.getValue(action))
            assertNotEquals(placeholder, details, "$action shows a description when selected")
            assertTrue(
                details.contains(description),
                "$action describes itself with its own text rather than another command's: $details",
            )
        }
    }

    @Test
    fun `every command and every category has its own words`() {
        // The keys are listed rather than derived, so a command added to the registry without an
        // explanation has to fail here instead of quietly showing the registry's English fallback.
        assertEquals(HotkeyAction.entries.toSet(), CommandExplorerText.descriptionKeys.keys, "one key per command")
        assertEquals(
            CommandRegistry.SPECS.mapNotNull { it.category }.filter { it.isNotEmpty() }.toSet(),
            CommandExplorerText.categoryKeys.keys,
            "one key per category the registry actually uses",
        )

        (CommandExplorerText.descriptionKeys.values + CommandExplorerText.categoryKeys.values).forEach { key ->
            assertNotEquals(key, localizer.getString(key), "$key has text")
        }
    }

    @Test
    fun `selection commands say the text comes from another application`() {
        val (_, panel) = panelFor()
        val generic = localizer.getString("settings_hotkeys.requirement_selection")
        val writing = localizer.getString("settings_hotkeys.requirement_writing")

        panel.select(HotkeyAction.SHOW_QUICK_TRANSLATE)
        assertTrue(panel.detailsTextForTest().contains(generic), "a selection command explains where its text comes from")

        // Rewrite and Summary are not ordinary workspace actions: they need a service and they
        // replace text somewhere else, so they carry the longer explanation.
        listOf(HotkeyAction.REPLACE_WITH_REWRITE, HotkeyAction.REPLACE_WITH_SUMMARY).forEach { action ->
            panel.select(action)
            val details = panel.detailsTextForTest()
            assertTrue(details.contains(writing), "$action says it acts on a selection elsewhere and needs a service")
            assertFalse(details.contains(generic), "$action does not fall back to the shorter note")
        }

        // A workspace command needs neither note.
        panel.select(HotkeyAction.SWAP_LANGUAGES)
        val workspace = panel.detailsTextForTest()
        assertFalse(workspace.contains(generic), "a workspace command does not claim to read a selection")
        assertFalse(workspace.contains(writing), "a workspace command does not claim to need a rewriter")
    }

    @Test
    fun `the default filter lists everything`() {
        val (_, panel) = panelFor()
        assertEquals(CommandRegistry.editableActions.toSet(), panel.listedActions().toSet(), "All lists everything")
        assertEquals(localizer.getString("settings_hotkeys.filter_all"), defaultFilterLabel(panel))
    }

    /** The one filter switch that starts selected. */
    private fun defaultFilterLabel(panel: KeyboardPanel): String = listOf(
        "settings_hotkeys.filter_all",
        "settings_hotkeys.filter_unassigned",
        "settings_hotkeys.filter_conflicts",
    ).map(localizer::getString).single { label -> onEdt { panel.filterButtonForTest(label).isSelected } }

    @Test
    fun `the unassigned filter lists only commands without a shortcut`() {
        val (_, panel) = panelFor()
        val expected = CommandRegistry.editableActions.filter { action ->
            Configuration.DEFAULT.effectiveHotkeys.find { it.action == action }?.hasBinding != true
        }

        onEdt { panel.filterButtonForTest(localizer.getString("settings_hotkeys.filter_unassigned")).doClick() }

        assertEquals(expected.toSet(), panel.listedActions().toSet(), "only the commands that ship unassigned are left")
        assertTrue(panel.listedActions().contains(HotkeyAction.REPLACE_WITH_REWRITE), "Rewrite is discoverable as unassigned")
        assertTrue(panel.listedActions().contains(HotkeyAction.REPLACE_WITH_SUMMARY), "Summary is discoverable as unassigned")
    }

    @Test
    fun `the conflicts filter lists only the commands in a conflict`() {
        val (_, panel) = panelFor(duplicateQuickTranslateChord())

        onEdt { panel.filterButtonForTest(localizer.getString("settings_hotkeys.filter_conflicts")).doClick() }

        assertEquals(
            setOf(HotkeyAction.SHOW_QUICK_TRANSLATE, HotkeyAction.LISTEN_TO_TEXT),
            panel.listedActions().toSet(),
        )
    }

    @Test
    fun `the conflict summary names the chord and both commands`() {
        val (_, panel) = panelFor(duplicateQuickTranslateChord())

        val summary = panel.conflictTextForTest()
        assertTrue(
            summary.contains(formatChord(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK)),
            "the shared combination is named, not left to be found by hand: $summary",
        )
        assertTrue(summary.contains(localizer.getString("settings_hotkeys.action_quick_translate")), "the first command is named")
        assertTrue(summary.contains(localizer.getString("settings_hotkeys.action_listen")), "the second command is named")
    }

    @Test
    fun `custom is reported as a description rather than offered as a preset`() {
        // A configuration that matches neither preset: Custom by definition.
        val custom = Configuration.DEFAULT.copy(
            hotkeys = Configuration.DEFAULT.hotkeys.map { binding ->
                if (binding.action == HotkeyAction.TRANSLATE) binding.copy(keyCode = KeyEvent.VK_F9, modifiers = 0) else binding
            }
        )
        val (settings, panel) = panelFor(custom)

        val combo = panel.presetComboForTest()
        assertEquals(2, combo.itemCount, "only Legacy and Modern can be applied")
        (0 until combo.itemCount).forEach { index ->
            assertNotEquals(HotkeyPresetKind.CUSTOM, combo.getItemAt(index), "Custom is not one of the choices")
        }
        assertEquals(-1, combo.selectedIndex, "nothing is selected, so nothing can clear the bindings")
        assertTrue(
            panel.presetStatusForTest().contains(localizer.getString("settings_hotkeys.preset_custom")),
            "the current state is still stated in words: ${panel.presetStatusForTest()}",
        )

        // Selecting Custom used to erase every shortcut. Nothing on this control can do that now.
        val before = settings.state.value.workingConfiguration.hotkeys
        onEdt { combo.selectedItem = null }
        assertEquals(before, settings.state.value.workingConfiguration.hotkeys, "no selection clears nothing")
    }

    @Test
    fun `applying a preset replaces the legacy bindings`() {
        val (settings, panel) = panelFor()

        onEdt { panel.presetComboForTest().selectedItem = HotkeyPresetKind.MODERN }
        onEdt { panel.render(settings.state.value) }

        assertEquals(
            HotkeyPresets.MODERN.sortedBy { it.action.ordinal },
            settings.state.value.workingConfiguration.hotkeys.sortedBy { it.action.ordinal },
            "Modern is applied to the legacy bucket",
        )
        assertEquals(
            HotkeyPresetKind.MODERN,
            HotkeyPresets.identify(settings.state.value.workingConfiguration.hotkeys),
            "and is then reported as the active preset",
        )
    }

    private fun duplicateQuickTranslateChord(): Configuration = Configuration.DEFAULT.copy(
        hotkeys = Configuration.DEFAULT.hotkeys.map { binding ->
            if (binding.action == HotkeyAction.LISTEN_TO_TEXT) {
                binding.copy(keyCode = KeyEvent.VK_Q, modifiers = InputEvent.CTRL_DOWN_MASK)
            } else {
                binding
            }
        }
    )

    private companion object {
        const val COL_ACTION = 0
        const val COL_HOTKEY = 1
    }
}
