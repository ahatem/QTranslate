package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.MinimizeButtonBehavior
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
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
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The General page is where a setting that depends on what the machine can offer says so instead
 * of promising something the application could not keep: "launch on system startup" is a Windows
 * feature, and the title-bar buttons can only hide the window into a tray that exists.
 */
class GeneralPanelPlatformTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(
            Files.createTempDirectory("qtranslate-general-platform").toFile(),
            LanguageTomlParser(),
            logger,
        )
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(configuration: Configuration = Configuration.DEFAULT): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-general-platform-store").toFile()
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

    private fun panel(
        supported: Boolean = true,
        trayAvailable: Boolean = true,
        settingsStore: SettingsStore = store(),
    ): GeneralPanel = onEdt {
        GeneralPanel(
            settingsStore,
            localizer,
            CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it },
            isLaunchOnStartupSupported = supported,
            isTrayAvailable = { trayAvailable },
        ).also {
            it.render(settingsStore.state.value)
        }
    }

    private fun launchCheckbox(panel: GeneralPanel): JCheckBox =
        descendants(panel)
            .filterIsInstance<JCheckBox>()
            .single { it.text == localizer.getString("settings_general.launch_on_startup") }

    /** The picker on the row labelled [label], reached through the label's own `labelFor`. */
    private fun rowCombo(panel: Container, label: String): JComboBox<*> {
        val rowLabel = descendants(panel).filterIsInstance<JLabel>().single { it.text == label }
        return rowLabel.labelFor as JComboBox<*>
    }

    /** The index of the entry whose rendered value names [text], as the pickers render it. */
    private fun indexNaming(combo: JComboBox<*>, text: String): Int =
        (0 until combo.itemCount).first { combo.getItemAt(it).toString().contains(text) }

    @Test
    fun `launch on startup is offered where the platform supports it`() {
        assertTrue(launchCheckbox(panel(supported = true)).isEnabled)
    }

    @Test
    fun `launch on startup is disabled where the platform cannot register it`() {
        assertFalse(launchCheckbox(panel(supported = false)).isEnabled)
    }

    @Test
    fun `the disabled toggle says which platform it belongs to`() {
        val hint = localizer.getString("settings_general.launch_on_startup_windows_only")
        assertTrue(
            descendants(panel(supported = false))
                .mapNotNull { it.textOf() }
                .any { it.contains(hint) },
            "a disabled platform-specific setting has to explain itself",
        )
    }

    // The title-bar buttons moved here from the Layout page; their capability gate came with them.

    @Test
    fun `choosing hide to tray is refused when no tray icon exists`() {
        val configuration = Configuration.DEFAULT.copy(
            minimizeButtonBehavior = MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR
        )
        val store = store(configuration)
        val hideToTray = localizer.getString("settings_window.minimize_behavior_hide")
        val minimize = rowCombo(
            panel(trayAvailable = false, settingsStore = store),
            localizer.getString("settings_window.minimize_button"),
        )

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
        val configuration = Configuration.DEFAULT.copy(
            minimizeButtonBehavior = MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR
        )
        val store = store(configuration)
        val hideToTray = localizer.getString("settings_window.minimize_behavior_hide")
        val minimize = rowCombo(
            panel(trayAvailable = true, settingsStore = store),
            localizer.getString("settings_window.minimize_button"),
        )

        onEdt { minimize.selectedIndex = indexNaming(minimize, hideToTray) }

        assertEquals(
            MinimizeButtonBehavior.HIDE_TO_TRAY,
            store.state.value.workingConfiguration.minimizeButtonBehavior,
        )
    }

    @Test
    fun `the page explains why the tray choice is unavailable`() {
        val hint = localizer.getString("settings_window.tray_unavailable_hint")
        val panel = panel(trayAvailable = false)

        assertTrue(
            descendants(panel).mapNotNull { it.textOf() }.any { it.contains(hint) },
            "a disabled choice has to say why",
        )
    }
}
