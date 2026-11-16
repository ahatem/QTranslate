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
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A Settings row is a label and a control sitting side by side. Without an association, a screen
 * reader announces a word and then an unlabelled control, and a keyboard user has no idea which
 * field they landed in. [JLabel.setLabelFor] is the association, and it is wired by the shared row
 * helper so every page gets it.
 */
class SettingsAccessibilityTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val directory by lazy { Files.createTempDirectory("qtranslate-settings-a11y").toFile() }

    private val localizer by lazy {
        LocalizationManager(directory, LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(): SettingsStore = runBlocking {
        val storage = Files.createTempDirectory("qtranslate-settings-a11y-store").toFile()
        val repository = SettingsRepository(storage, Json { ignoreUnknownKeys = true }, logger)
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

    private fun labelText(panel: Container, text: String): JLabel =
        descendants(panel).filterIsInstance<JLabel>().first { it.text == text }

    @Test
    fun `a row label names the control beside it`() {
        val store = store()
        val panel = onEdt {
            GeneralPanel(
                store,
                localizer,
                CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it },
            ).also { panel -> panel.render(store.state.value) }
        }

        val readAloud = labelText(panel, localizer.getString("settings_general.selection_read_aloud"))
        assertNotNull(readAloud.labelFor, "the label is associated with its picker")
        assertTrue(readAloud.labelFor is JComboBox<*>, "and it names the picker, not the page")
    }

    @Test
    fun `every labelled row on the network page is associated`() {
        val store = store()
        val panel = onEdt { NetworkPanel(store, localizer).also { panel -> panel.render(store.state.value) } }

        val proxyAddress = labelText(panel, localizer.getString("settings_network.proxy_url"))
        assertNotNull(proxyAddress.labelFor)
        assertTrue(proxyAddress.labelFor is JTextField, "the label points at the address field")

        val associated = descendants(panel)
            .filterIsInstance<JLabel>()
            .count { it.labelFor != null }
        assertTrue(associated >= 5, "the network page's rows are all associated ($associated)")
    }

    @Test
    fun `an icon-only row action carries a name for assistive technology`() {
        val store = store()
        val panel = onEdt {
            GeneralPanel(
                store,
                localizer,
                CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it },
                openEditor = { },
            ).also { panel -> panel.render(store.state.value) }
        }

        val iconOnly = descendants(panel)
            .filterIsInstance<JButton>()
            .filter { it.text.isNullOrBlank() && !it.toolTipText.isNullOrBlank() }

        assertTrue(iconOnly.isNotEmpty(), "the language row has icon-only actions")
        assertTrue(
            iconOnly.all { !it.accessibleContext.accessibleName.isNullOrBlank() },
            "a glyph with only a tooltip is unlabelled to a screen reader",
        )
    }
    @Test
    fun `network explanations reach a control that takes focus`() {
        val store = store()
        val panel = onEdt { NetworkPanel(store, localizer).also { panel -> panel.render(store.state.value) } }

        val explained = descendants(panel)
            .filterIsInstance<JSpinner>()
            .filter { !it.accessibleContext.accessibleDescription.isNullOrBlank() }

        assertTrue(explained.isNotEmpty(), "tooltip-only help is attached to the controls that take focus")
        assertTrue(
            explained.any { (it.accessibleContext.accessibleDescription ?: "").length > 40 },
            "the explanation itself is attached, not its key",
        )
    }
}