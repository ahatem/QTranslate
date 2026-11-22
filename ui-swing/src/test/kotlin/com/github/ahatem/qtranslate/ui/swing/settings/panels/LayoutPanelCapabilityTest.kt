package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.HistoryPlacement
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.ServicePlacement
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.data.WorkspaceLayout
import com.github.ahatem.qtranslate.core.settings.data.withWorkspaceLayout
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
import java.nio.file.Files
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Layout page exposes every region's real choice, and gates only the value that would
 * otherwise be dead. Selector placement stays enabled even on a comparison board (RELOCATED is a
 * real choice there); appearance is the only row that can have nothing to change, so it is
 * disabled with a reason unless the bottom strip is actually shown.
 */
class LayoutPanelCapabilityTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-layout-test").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(config: Configuration): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-layout-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, config)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    private fun rowCombo(panel: Container, label: String): JComboBox<*> {
        val rowLabel = descendants(panel).filterIsInstance<JLabel>().single { it.text == label }
        return rowLabel.labelFor as JComboBox<*>
    }

    private fun panel(store: SettingsStore): LayoutPanel = onEdt {
        LayoutPanel(store, localizer).also { it.render(store.state.value) }
    }

    private fun writeWorkspace(store: SettingsStore, panel: LayoutPanel, transform: (WorkspaceLayout) -> WorkspaceLayout) =
        onEdt {
            store.dispatch(SettingsIntent.TransformDraft { it.withWorkspaceLayout(transform(it.workspaceLayout)) })
            panel.render(store.state.value)
        }

    @Test
    fun `strip labels are only offered while the bottom strip is shown`() {
        val store = store(Configuration.DEFAULT)
        val layoutPanel = panel(store)
        val appearance = rowCombo(layoutPanel, localizer.getString("settings_window.layout_row_appearance"))

        // The default picker has no strip, so the appearance value would change nothing.
        assertFalse(appearance.isEnabled, "a picker has no strip whose labels can change")

        writeWorkspace(store, layoutPanel) { it.copy(services = ServicePlacement.BOTTOM_STRIP) }
        assertTrue(appearance.isEnabled, "the bottom strip's labels can now be tuned")
    }

    @Test
    fun `history placement survives a change to comparison`() {
        val store = store(Configuration.DEFAULT)
        val layoutPanel = panel(store)
        val history = rowCombo(layoutPanel, localizer.getString("settings_window.layout_row_history"))

        writeWorkspace(store, layoutPanel) { it.copy(history = HistoryPlacement.BOTTOM_ROW) }
        assertEquals(
            HistoryPlacement.BOTTOM_ROW,
            store.state.value.workingConfiguration.workspaceLayout.history,
        )
        assertTrue(history.isEnabled, "every pane layout has a history placement to choose")

        writeWorkspace(store, layoutPanel) { it.copy(panes = PaneLayout.COMPARISON) }
        assertEquals(
            HistoryPlacement.BOTTOM_ROW,
            store.state.value.workingConfiguration.workspaceLayout.history,
            "changing panes does not silently reset the history choice",
        )
    }
}
