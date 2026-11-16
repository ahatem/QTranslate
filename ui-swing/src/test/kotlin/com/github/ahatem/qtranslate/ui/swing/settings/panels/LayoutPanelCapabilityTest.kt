package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.MinimizeButtonBehavior
import com.github.ahatem.qtranslate.core.settings.data.ServiceSelectorStyle
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
 * Two controls on the Window page depend on something other than their own value.
 *
 * The classic selector appearance only exists for the classic selector, and hiding to the tray
 * only exists when there is a tray to hide into. Both used to be live regardless, which made one
 * a no-op and the other an unrecoverable action.
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

    private fun store(config: Configuration): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-layout-capability-store").toFile()
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

    /** The one picker offering an item whose rendered text contains [text]. */
    private fun comboContaining(panel: Container, text: String): JComboBox<*> =
        descendants(panel).filterIsInstance<JComboBox<*>>().single { combo ->
            (0 until combo.itemCount).any { index ->
                combo.getItemAt(index)?.toString()?.contains(text) == true
            }
        }

    private fun indexContaining(combo: JComboBox<*>, text: String): Int =
        (0 until combo.itemCount).first { combo.getItemAt(it)?.toString()?.contains(text) == true }

    private fun panel(store: SettingsStore, trayAvailable: Boolean = true): LayoutPanel = onEdt {
        LayoutPanel(store, localizer, isTrayAvailable = { trayAvailable }).also {
            it.render(store.state.value)
        }
    }

    @Test
    fun `classic appearance is only offered while the classic selector is selected`() {
        val store = store(Configuration.DEFAULT)
        val layoutPanel = panel(store)
        val appearance = comboContaining(
            layoutPanel,
            localizer.getString("settings_window.service_selector_icons_text"),
        )

        assertFalse(appearance.isEnabled, "the default Enhanced selector has no classic appearance")

        onEdt {
            store.dispatch(
                SettingsIntent.TransformDraft { it.copy(serviceSelectorStyle = ServiceSelectorStyle.CLASSIC) }
            )
            layoutPanel.render(store.state.value)
        }

        assertTrue(appearance.isEnabled, "choosing the classic selector makes its appearance meaningful")
    }

    @Test
    fun `choosing hide to tray is refused when no tray icon exists`() {
        val config = Configuration.DEFAULT.copy(minimizeButtonBehavior = MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR)
        val store = store(config)
        val minimize = comboContaining(
            panel(store, trayAvailable = false),
            localizer.getString("settings_window.minimize_behavior_hide"),
        )

        onEdt { minimize.selectedIndex = indexContaining(minimize, localizer.getString("settings_window.minimize_behavior_hide")) }

        assertEquals(
            MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR,
            store.state.value.workingConfiguration.minimizeButtonBehavior,
            "an unavailable choice is not written to the draft",
        )
        assertFalse(store.state.value.isDirty, "and the dialog does not pretend it changed anything")
    }

    @Test
    fun `hide to tray is written when a tray icon exists`() {
        val config = Configuration.DEFAULT.copy(minimizeButtonBehavior = MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR)
        val store = store(config)
        val minimize = comboContaining(
            panel(store, trayAvailable = true),
            localizer.getString("settings_window.minimize_behavior_hide"),
        )

        onEdt { minimize.selectedIndex = indexContaining(minimize, localizer.getString("settings_window.minimize_behavior_hide")) }

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
            descendants(panel).filterIsInstance<JLabel>().any { it.text?.contains(hint) == true },
            "a disabled choice has to say why",
        )
    }
}
