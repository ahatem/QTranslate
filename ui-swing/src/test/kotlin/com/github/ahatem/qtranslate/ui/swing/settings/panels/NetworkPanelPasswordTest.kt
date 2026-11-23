package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import javax.swing.JButton
import javax.swing.JPasswordField
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The proxy password is a credential: it lives in the secret store and removing it is an explicit action. */
class NetworkPanelPasswordTest {

    private val fixture = SettingsPanelFixture()
    private val localizer get() = fixture.localizer

    @AfterTest
    fun tearDown() = fixture.close()

    /** Proxy fields are only actionable while the proxy is on, which is the state this test is about. */
    private fun store(): SettingsStore =
        fixture.store(Configuration.FRESH_INSTALL.copy(network = NetworkConfig(proxyEnabled = true)))

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

        assertTrue(access.writes.isNotEmpty(), "the clear action writes to the secret store")
        assertEquals("", access.value, "an empty value is how the store removes a secret")
        assertTrue(
            descendants(panel).filterIsInstance<JPasswordField>().all { it.password.isEmpty() },
            "the field no longer shows a password that is gone",
        )
    }
}
