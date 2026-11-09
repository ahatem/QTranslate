package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyConflictKind
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settings panel is where conflicts are surfaced: while the working draft carries
 * enabled bindings that contend for one combination, the panel reports them.
 */
class KeyboardPanelConflictTest {
    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-kbd-conflicts").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun store(configuration: Configuration): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-kbd-conflicts-store").toFile()
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

    private fun panelFor(configuration: Configuration): Pair<SettingsStore, KeyboardPanel> {
        val settings = store(configuration)
        val panel = onEdt { KeyboardPanel(settings, localizer) }
        onEdt { panel.render(settings.state.value) }
        return settings to panel
    }

    @Test
    fun `default configuration reports no conflicts`() {
        val (_, panel) = panelFor(Configuration.DEFAULT)
        assertTrue(onEdt { panel.conflictsForTest() }.isEmpty())
    }

    @Test
    fun `duplicate global bindings in the draft are reported`() {
        // Rebind LISTEN_TO_TEXT onto Ctrl+Q, the combination SHOW_QUICK_TRANSLATE already
        // holds globally.
        val hotkeys = Configuration.DEFAULT.hotkeys.map { binding ->
            if (binding.action == HotkeyAction.LISTEN_TO_TEXT) {
                binding.copy(keyCode = KeyEvent.VK_Q, modifiers = InputEvent.CTRL_DOWN_MASK)
            } else {
                binding
            }
        }
        val configuration = Configuration.DEFAULT.copy(hotkeys = hotkeys)
        val (_, panel) = panelFor(configuration)

        val conflicts = onEdt { panel.conflictsForTest() }
        assertEquals(1, conflicts.size)
        assertEquals(HotkeyConflictKind.DUPLICATE_GLOBAL, conflicts[0].kind)
        assertEquals(
            setOf(HotkeyAction.SHOW_QUICK_TRANSLATE, HotkeyAction.LISTEN_TO_TEXT),
            conflicts[0].actions,
        )
    }

    @Test
    fun `a fixture built from DEFAULTS plus a duplicate edit conflicts only on that edit`() {
        val base = HotkeyBinding.DEFAULTS
        val edited = base.map { binding ->
            if (binding.action == HotkeyAction.TRANSLATE) {
                binding.copy(keyCode = KeyEvent.VK_Q, modifiers = InputEvent.CTRL_DOWN_MASK)
            } else {
                binding
            }
        }
        val configuration = Configuration.DEFAULT.copy(
            hotkeys = edited.filter { it.action in HotkeyBinding.LEGACY_ACTIONS },
            ocrHotkeys = edited.filter { it.action in HotkeyBinding.OCR_ACTIONS },
        )
        val (_, panel) = panelFor(configuration)
        val conflicts = onEdt { panel.conflictsForTest() }
        assertTrue(conflicts.any { it.kind == HotkeyConflictKind.LOCAL_GLOBAL_COLLISION })
    }
}
