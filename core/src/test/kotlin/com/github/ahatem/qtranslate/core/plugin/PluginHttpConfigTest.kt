package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.api.plugin.Plugin
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.plugin.http.HttpClientConfig
import com.github.ahatem.qtranslate.core.plugin.lifecycle.PluginLifecycleHandler
import com.github.ahatem.qtranslate.core.plugin.storage.AppSecretStore
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.plugin.text.PluginTextResolver
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginHttpConfigTest {

    @Test
    fun `repeated context config creation does not reload settings`() = runBlocking {
        val logger = CountingLogger()
        val manager = manager(SettingsRepository(tempDir(), json, logger)) {
            NetworkConfig(proxyEnabled = true, proxyUrl = "http://proxy.example:3128", requestTimeoutSeconds = 7)
        }

        repeat(3) { manager.currentHttpConfig() }

        assertEquals(0, logger.initialLoads)
    }

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

    @Test
    fun `lifecycle handler requests config for every context`() = runBlocking {
        val directory = tempDir()
        var calls = 0
        val handler = PluginLifecycleHandler(
            appDataDirectory = directory,
            pluginKeyValueStore = PluginKeyValueStore(directory),
            notificationBus = NotificationBus(),
            textResolver = NoTextResolver,
            loggerFactory = SilentLoggerFactory,
            httpConfig = { calls++; HttpClientConfig() }
        )

        repeat(3) { handler.createContext(result("plugin-$it")) }

        assertEquals(3, calls)
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

    private fun result(pluginId: String) = LoadedPluginResult(
        plugin = NoopPlugin,
        manifest = PluginManifest(pluginId, pluginId, "1.0.0", "test", "test", "1.0.0"),
        jarFile = File("$pluginId.jar"),
        jarHash = "0",
        classLoader = NoopPlugin.javaClass.classLoader
    )

    private object NoopPlugin : Plugin<PluginSettings.None> {
        override suspend fun initialize(context: PluginContext): Result<Unit, ServiceError> = Ok(Unit)
        override fun getServices(): List<Service> = emptyList()
        override fun getSettings(): PluginSettings.None = PluginSettings.None
    }

    private object NoTextResolver : PluginTextResolver {
        override fun resolve(pluginId: String, text: DisplayText): String = text.fallback
        override fun onPluginLoaded(pluginId: String, classLoader: ClassLoader) = Unit
        override fun onPluginRemoved(pluginId: String) = Unit
    }

    private object SilentLoggerFactory : LoggerFactory {
        override fun getLogger(name: String): Logger = SilentLogger
    }

    private object SilentLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private class CountingLogger : Logger {
        var initialLoads = 0
            private set

        override fun debug(message: String) = Unit
        override fun info(message: String) {
            if (message == "Initial configuration loaded successfully") initialLoads++
        }
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        fun tempDir() = Files.createTempDirectory("qtranslate-http-config-test").toFile()
    }
}
