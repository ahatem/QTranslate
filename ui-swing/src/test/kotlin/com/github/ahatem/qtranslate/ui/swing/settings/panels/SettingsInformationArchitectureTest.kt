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
import javax.swing.JSpinner
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A setting belongs on the page where a user would look for it, and expert controls belong behind
 * a disclosure rather than in everyone's face.
 */
class SettingsInformationArchitectureTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(
            Files.createTempDirectory("qtranslate-settings-ia").toFile(),
            LanguageTomlParser(),
            logger,
        )
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-settings-ia-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(Configuration.FRESH_INSTALL)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, Configuration.FRESH_INSTALL)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    private fun effectivelyVisible(component: Component): Boolean {
        var current: Component? = component
        while (current != null) {
            if (!current.isVisible) return false
            current = current.parent
        }
        return true
    }

    @Test
    fun `expert network tuning starts folded away and one control reveals it`() {
        val store = store()
        val panel = onEdt { NetworkPanel(store, localizer).also { it.render(store.state.value) } }
        val spinners = descendants(panel).filterIsInstance<JSpinner>()

        assertTrue(spinners.isNotEmpty(), "the timeouts and retries are on the page")
        assertTrue(
            spinners.none { effectivelyVisible(it) },
            "but not before the user asks for them",
        )

        val toggle = descendants(panel)
            .filterIsInstance<JCheckBox>()
            .single { it.text == localizer.getString("settings_network.advanced_group") }
        assertFalse(toggle.isSelected, "the disclosure starts closed")
        onEdt { toggle.doClick() }

        assertTrue(toggle.isSelected)
        assertTrue(spinners.all { effectivelyVisible(it) }, "opening it shows the whole group")
    }
}
