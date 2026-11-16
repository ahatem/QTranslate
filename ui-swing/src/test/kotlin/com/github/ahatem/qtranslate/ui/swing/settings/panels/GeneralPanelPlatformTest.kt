package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
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
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Launch on system startup" is a Windows feature, so offering it everywhere promised something
 * the application could not keep.
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

    private fun store(): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-general-platform-store").toFile()
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

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    private fun panel(supported: Boolean): GeneralPanel {
        val store = store()
        return onEdt {
            GeneralPanel(store, localizer, isLaunchOnStartupSupported = supported).also {
                it.render(store.state.value)
            }
        }
    }

    private fun launchCheckbox(panel: GeneralPanel): JCheckBox =
        descendants(panel)
            .filterIsInstance<JCheckBox>()
            .single { it.text == localizer.getString("settings_general.launch_on_startup") }

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
                .filterIsInstance<JLabel>()
                .any { it.text?.contains(hint) == true },
            "a disabled platform-specific setting has to explain itself",
        )
    }
}
