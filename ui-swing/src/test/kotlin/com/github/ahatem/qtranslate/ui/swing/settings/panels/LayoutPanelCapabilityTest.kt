package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.MinimizeButtonBehavior
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.data.withLayoutConfig
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two controls on the Layout page depend on something other than their own value.
 *
 * The service selector means nothing on a comparison board — there is no widget to configure,
 * because that arrangement picks the primary translator from the result headers — and hiding to
 * the tray only exists when there is a tray to hide into. Both used to be live regardless, which
 * made one a no-op and the other an unrecoverable action.
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
        LocalizationManager(
            Files.createTempDirectory("qtranslate-layout-capability").toFile(),
            LanguageTomlParser(),
            logger,
        )
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(configuration: Configuration): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-layout-capability-store").toFile()
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

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    /**
     * The picker on the row labelled [label], reached through the label's own `labelFor`.
     *
     * Addressed by row rather than by contents: the page now has five pickers, two of which can
     * offer an entry with the same words, so "the picker containing X" is ambiguous.
     */
    private fun rowCombo(panel: Container, label: String): JComboBox<*> {
        val rowLabel = descendants(panel).filterIsInstance<JLabel>().single { it.text == label }
        return rowLabel.labelFor as JComboBox<*>
    }

    /** The index of the entry whose rendered value names [text], as the pickers render it. */
    private fun indexNaming(combo: JComboBox<*>, text: String): Int =
        (0 until combo.itemCount).first { combo.getItemAt(it).toString().contains(text) }

    private fun panel(store: SettingsStore, trayAvailable: Boolean = true): LayoutPanel = onEdt {
        LayoutPanel(store, localizer, isTrayAvailable = { trayAvailable }).also {
            it.render(store.state.value)
        }
    }

    private fun writePane(store: SettingsStore, panel: LayoutPanel, pane: PaneLayout) = onEdt {
        store.dispatch(
            SettingsIntent.TransformDraft { it.withLayoutConfig(it.layoutConfig.copy(paneLayout = pane)) }
        )
        panel.render(store.state.value)
    }

    @Test
    fun `the service selector is only offered while the panes are not compared`() {
        val store = store(Configuration.DEFAULT)
        val layoutPanel = panel(store)
        val selector = rowCombo(layoutPanel, localizer.getString("settings_window.layout_row_selector"))

        assertTrue(selector.isEnabled, "a stacked arrangement has a selector to configure")
        assertNull(selector.toolTipText, "and nothing to explain while it is usable")

        writePane(store, layoutPanel, PaneLayout.COMPARISON)

        assertFalse(selector.isEnabled, "a comparison board has no selector widget at all")
        assertEquals(
            localizer.getString("settings_window.layout_selector_absent"),
            selector.toolTipText,
            "a disabled row has to say why",
        )
    }

    @Test
    fun `the service selector comes back when the panes stop being compared`() {
        val compared = Configuration.DEFAULT.withLayoutConfig(
            Configuration.DEFAULT.layoutConfig.copy(paneLayout = PaneLayout.COMPARISON)
        )
        val store = store(compared)
        val layoutPanel = panel(store)
        val selector = rowCombo(layoutPanel, localizer.getString("settings_window.layout_row_selector"))
        assertFalse(selector.isEnabled)

        writePane(store, layoutPanel, PaneLayout.STACKED)

        assertTrue(selector.isEnabled, "the row is governed by the arrangement, not by a one-way switch")
        assertNull(selector.toolTipText)
    }

    @Test
    fun `choosing hide to tray is refused when no tray icon exists`() {
        val configuration = Configuration.DEFAULT.copy(minimizeButtonBehavior = MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR)
        val store = store(configuration)
        val hideToTray = localizer.getString("settings_window.minimize_behavior_hide")
        val minimize = rowCombo(panel(store, trayAvailable = false), localizer.getString("settings_window.minimize_button"))

        onEdt { minimize.selectedIndex = indexNaming(minimize, hideToTray) }

        assertEquals(
            MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR,
            store.state.value.workingConfiguration.minimizeButtonBehavior,
            "an unavailable choice is not written to the draft",
        )
        assertFalse(store.state.value.isDirty, "and the dialog does not pretend it changed anything")
    }

    @Test
    fun `hide to tray is written when a tray icon exists`() {
        val configuration = Configuration.DEFAULT.copy(minimizeButtonBehavior = MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR)
        val store = store(configuration)
        val hideToTray = localizer.getString("settings_window.minimize_behavior_hide")
        val minimize = rowCombo(panel(store, trayAvailable = true), localizer.getString("settings_window.minimize_button"))

        onEdt { minimize.selectedIndex = indexNaming(minimize, hideToTray) }

        assertEquals(
            MinimizeButtonBehavior.HIDE_TO_TRAY,
            store.state.value.workingConfiguration.minimizeButtonBehavior,
        )
    }

    @Test
    fun `the page explains why the tray choice is unavailable`() {
        val store = store(Configuration.DEFAULT)
        val hint = localizer.getString("settings_window.tray_unavailable_hint")
        val panel = panel(store, trayAvailable = false)

        assertTrue(
            descendants(panel).mapNotNull { it.textOf() }.any { it.contains(hint) },
            "a disabled choice has to say why",
        )
    }
}
