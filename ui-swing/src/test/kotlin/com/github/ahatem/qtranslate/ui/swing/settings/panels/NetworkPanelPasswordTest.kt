package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
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
import javax.swing.JPasswordField
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The proxy password is a credential, not a preference: it lives in the secret store, it is not
 * part of any reset, and removing it has to be an action the user can find.
 */
class NetworkPanelPasswordTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(
            Files.createTempDirectory("qtranslate-network-password").toFile(),
            LanguageTomlParser(),
            logger,
        )
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-network-password-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        // Proxy fields are only actionable while the proxy is on, which is the state this test is about.
        val config = Configuration.FRESH_INSTALL.copy(network = NetworkConfig(proxyEnabled = true))
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

    private class RecordingAccess(var value: String) : NetworkPanel.PasswordAccess {
        val writes = mutableListOf<String>()
        override fun read(): String = value
        override fun write(value: String) {
            this.value = value
            writes += value
        }
    }

    @Test
    fun `clearing the stored password is an explicit action`() {
        val access = RecordingAccess("s3cret")
        val store = store()
        val panel = onEdt {
            NetworkPanel(store, localizer, proxyPassword = access).also { it.render(store.state.value) }
        }
        val clear = descendants(panel)
            .filterIsInstance<JButton>()
            .single { it.text == localizer.getString("settings_network.proxy_password_clear") }

        onEdt { clear.doClick() }

        // Clearing the field also fires the field's own edit path; what matters is that the stored
        // credential is now empty, which is how the secret store removes it.
        assertTrue(access.writes.isNotEmpty(), "the clear action writes to the secret store")
        assertEquals("", access.value, "an empty value is how the store removes a secret")
        assertTrue(
            descendants(panel).filterIsInstance<JPasswordField>().all { it.password.isEmpty() },
            "the field no longer shows a password that is gone",
        )
    }

    @Test
    fun `the password field says what an empty value means`() {
        val access = RecordingAccess("s3cret")
        val store = store()
        val panel = onEdt {
            NetworkPanel(store, localizer, proxyPassword = access).also { it.render(store.state.value) }
        }
        val hintPresent = descendants(panel).any {
            (it as? javax.swing.JLabel)?.text?.contains(
                localizer.getString("settings_network.proxy_password_hint")
            ) == true
        }

        assertTrue(hintPresent, "empty-means-none and the clear action are explained, not guessed")
    }
}
