package com.github.ahatem.qtranslate.ui.swing.settings.panels

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
import java.nio.file.Files
import javax.swing.JTable
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A hotkey is configurable from this panel only if its action is in [KeyboardPanel]'s action list,
 * because that list is what rows are built from. #159 added six window-local actions that were
 * bound, migrated and localized, but never listed, so they ran on their defaults and could not be
 * changed. Nothing else in the system fails when an action is missing from that list, which is why
 * it is asserted here rather than left to review.
 *
 * Assertions read the binding each row carries rather than the action names in the first column, so
 * they hold against the row-to-action mapping itself: a row whose label and binding disagree is the
 * same defect as a row that is absent, and would otherwise pass.
 */
class KeyboardPanelActionListTest {
    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    /** English resolves from the strings embedded in the JAR, so these are the real default labels. */
    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-kbd-actions").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun store(): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-kbd-actions-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(Configuration.DEFAULT)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, Configuration.DEFAULT)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun renderedTable(): JTable {
        val settings = store()
        val panel = onEdt { KeyboardPanel(settings, localizer) }
        onEdt { panel.render(settings.state.value) }
        return panel.tableForTest()
    }

    private fun JTable.column(index: Int): List<Any?> = (0 until rowCount).map { getValueAt(it, index) }

    /** The binding each row displays, which render() fills from the configuration for that row. */
    private fun JTable.bindings(): List<HotkeyBinding?> = column(COL_HOTKEY).map { it as? HotkeyBinding }

    private fun JTable.actions(): List<HotkeyAction?> = bindings().map { it?.action }

    private fun JTable.labels(): List<String> = column(COL_ACTION).map { it.toString() }

    @Test
    fun `the six window-local actions added in 159 are listed`() {
        val listed = renderedTable().actions()

        listOf(
            HotkeyAction.COPY_TRANSLATION,
            HotkeyAction.CLEAR_INPUT,
            HotkeyAction.SWAP_LANGUAGES,
            HotkeyAction.OPEN_SETTINGS,
            HotkeyAction.SHOW_HISTORY,
            HotkeyAction.TRANSLATE_DOCUMENT
        ).forEach { action ->
            assertTrue(action in listed, "$action needs a row so its shortcut can be changed")
        }
    }

    @Test
    fun `every bindable action is listed exactly once, in a stable order`() {
        // The invariant behind the bug. SHOW_MAIN_WINDOW is the one exclusion: it has its own row
        // above the table with Edit/Clear buttons and the Double Ctrl option.
        val bindableInOrder = listOf(
            HotkeyAction.SHOW_QUICK_TRANSLATE,
            HotkeyAction.LISTEN_TO_TEXT,
            HotkeyAction.OPEN_OCR,
            HotkeyAction.REPLACE_WITH_TRANSLATION,
            HotkeyAction.CYCLE_TARGET_LANGUAGE,
            HotkeyAction.SHOW_DICTIONARY,
            HotkeyAction.SHOW_IMAGES,
            HotkeyAction.TRANSLATE,
            HotkeyAction.FOCUS_INPUT,
            HotkeyAction.FOCUS_OUTPUT,
            HotkeyAction.FOCUS_EXTRA_OUTPUT,
            HotkeyAction.COPY_TRANSLATION,
            HotkeyAction.CLEAR_INPUT,
            HotkeyAction.SWAP_LANGUAGES,
            HotkeyAction.OPEN_SETTINGS,
            HotkeyAction.SHOW_HISTORY,
            HotkeyAction.TRANSLATE_DOCUMENT
        )

        assertEquals(bindableInOrder, renderedTable().actions(), "row order is what a row index maps back to")
        assertEquals(
            HotkeyAction.entries.toSet(),
            bindableInOrder.toSet() + HotkeyAction.SHOW_MAIN_WINDOW,
            "the list covers every bindable action, so none can be added without a row"
        )
    }

    @Test
    fun `Show Main Window is not given a second row`() {
        assertFalse(
            HotkeyAction.SHOW_MAIN_WINDOW in renderedTable().actions(),
            "it is edited through its own row above the table"
        )
    }

    @Test
    fun `every row arrives with the default binding for a distinct action`() {
        val bindings = renderedTable().bindings()

        val unbound = bindings.indices.filter { bindings[it]?.hasBinding != true }
        assertTrue(unbound.isEmpty(), "a listed action should arrive with a default binding: rows $unbound")

        val actions = bindings.map { it?.action }
        assertEquals(actions.size, actions.toSet().size, "no action is bound by two rows: $actions")
    }

    @Test
    fun `the six actions added in 159 are listed as local`() {
        val table = renderedTable()
        val scopesByAction = table.bindings().filterNotNull().associate { it.action to it.scope }

        listOf(
            HotkeyAction.COPY_TRANSLATION,
            HotkeyAction.CLEAR_INPUT,
            HotkeyAction.SWAP_LANGUAGES,
            HotkeyAction.OPEN_SETTINGS,
            HotkeyAction.SHOW_HISTORY,
            HotkeyAction.TRANSLATE_DOCUMENT
        ).forEach { action ->
            assertEquals(HotkeyScope.LOCAL, scopesByAction[action], "$action acts on the focused window")
        }
    }

    @Test
    fun `every action name renders as text rather than a bare lookup key`() {
        // A missing string used to fall through as the raw dotted key, which is how the #159 labels
        // once appeared in the table. Checking the shape rather than the wording keeps this from
        // failing when a label is reworded.
        renderedTable().labels().forEach { label ->
            assertTrue(label.isNotBlank(), "an action name resolved to nothing")
            assertFalse(Regex("""^\w+(\.\w+)+$""").matches(label), "\"$label\" looks like an unresolved key")
        }
    }

    private companion object {
        const val COL_ACTION = 0
        const val COL_HOTKEY = 1
    }
}
