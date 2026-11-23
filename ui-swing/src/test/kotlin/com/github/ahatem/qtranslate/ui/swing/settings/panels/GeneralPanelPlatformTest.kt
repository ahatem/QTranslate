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
    fun `launch on startup is enabled only where the platform can register it`() {
        assertTrue(launchCheckbox(panel(supported = true)).isEnabled)
        assertFalse(launchCheckbox(panel(supported = false)).isEnabled)
    }

    // The title-bar buttons moved here from the Layout page; their capability gate came with them.

    @Test
    fun `hide to tray is written only when a tray icon exists`() {
        val expected = mapOf(
            true to MinimizeButtonBehavior.HIDE_TO_TRAY,
            // An unavailable choice is not written to the draft, and the dialog stays clean.
            false to MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR,
        )
        expected.forEach { (trayAvailable, behavior) ->
            val store = store(
                Configuration.DEFAULT.copy(minimizeButtonBehavior = MinimizeButtonBehavior.MINIMIZE_TO_TASKBAR)
            )
            val hideToTray = localizer.getString("settings_window.minimize_behavior_hide")
            val minimize = rowCombo(
                panel(trayAvailable = trayAvailable, settingsStore = store),
                localizer.getString("settings_window.minimize_button"),
            )

            onEdt { minimize.selectedIndex = indexNaming(minimize, hideToTray) }

            assertEquals(
                behavior,
                store.state.value.workingConfiguration.minimizeButtonBehavior,
                "tray available = $trayAvailable",
            )
            assertEquals(trayAvailable, store.state.value.isDirty, "tray available = $trayAvailable")
        }
    }
}
