package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.core.testing.SilentLoggerFactory
import com.github.ahatem.qtranslate.core.testing.SilentLogger
import com.github.ahatem.qtranslate.core.plugin.storage.AppSecretStore
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginHttpConfigTest {

    @Test
    fun `a later context sees updated network settings`() = runBlocking {
        var current = NetworkConfig(requestTimeoutSeconds = 5)
        val manager = manager(SettingsRepository(tempDir(), json, SilentLogger)) { current }

        assertEquals(5_000, manager.currentHttpConfig().requestTimeoutMillis)
        current = NetworkConfig(requestTimeoutSeconds = 99)
        assertEquals(99_000, manager.currentHttpConfig().requestTimeoutMillis)
    }

    @Test
    fun `proxy credentials come from the secret store`() = runBlocking {
        val store = PluginKeyValueStore(tempDir())
        AppSecretStore(store).put(NetworkConfig.proxyPasswordKey, "s3cret")
        val manager = manager(SettingsRepository(tempDir(), json, SilentLogger), store) {
            NetworkConfig(proxyEnabled = true, proxyUrl = "http://proxy.example:3128", proxyUsername = "alice")
        }

        val proxy = manager.currentHttpConfig().proxy
        assertEquals("http://proxy.example:3128", proxy?.url)
        assertEquals("alice", proxy?.username)
        assertEquals("s3cret", proxy?.password)
    }

    @Test
    fun `disabled proxy does not resolve a stale password`() = runBlocking {
        val store = PluginKeyValueStore(tempDir())
        AppSecretStore(store).put(NetworkConfig.proxyPasswordKey, "stale")
        val manager = manager(SettingsRepository(tempDir(), json, SilentLogger), store) {
            NetworkConfig(proxyEnabled = false)
        }

        assertNull(manager.currentHttpConfig().proxy)
    }

    private fun manager(
        repository: SettingsRepository,
        store: PluginKeyValueStore = PluginKeyValueStore(tempDir()),
        networkConfig: () -> NetworkConfig
    ) = PluginManager(
        appDataDirectory = tempDir(),
        settingsRepository = repository,
        pluginFingerprintRepository = PluginFingerprintRepository(tempDir(), json),
        pluginKeyValueStore = store,
        loggerFactory = SilentLoggerFactory,
        notificationBus = NotificationBus(),
        networkConfig = networkConfig
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        fun tempDir() = Files.createTempDirectory("qtranslate-http-config-test").toFile()
    }
}
