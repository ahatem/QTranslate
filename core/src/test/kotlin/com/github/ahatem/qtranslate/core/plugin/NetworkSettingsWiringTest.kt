package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.core.plugin.http.HttpClientConfig
import com.github.ahatem.qtranslate.core.plugin.http.toHttpClientConfig
import com.github.ahatem.qtranslate.core.plugin.storage.AppSecretStore
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.plugin.text.PluginTextResolver
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * That what the Network settings page stores is what a plugin's client is actually built with.
 *
 * Every link in this chain had its own tests and the chain itself had none, which is the shape of
 * a feature that is entirely correct and entirely disconnected: the page saved, the mapping
 * converted, the client obeyed, and nothing checked that the three were joined. This asserts the
 * join.
 */
class NetworkSettingsWiringTest {

    private object NoTextResolver : PluginTextResolver {
        override fun resolve(pluginId: String, text: DisplayText): String = text.fallback
        override fun onPluginLoaded(pluginId: String, classLoader: ClassLoader) = Unit
        override fun onPluginRemoved(pluginId: String) = Unit
    }

    private object SilentLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private class NoopClient : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>, queryParams: Map<String, Any?>):
            Result<String, ServiceError> = Ok("")

        override suspend fun post(url: String, headers: Map<String, String>, body: String?, queryParams: Map<String, Any?>):
            Result<String, ServiceError> = Ok("")

        override suspend fun postForm(url: String, formData: Map<String, String>, headers: Map<String, String>, queryParams: Map<String, Any?>, cookies: Map<String, String>):
            Result<String, ServiceError> = Ok("")

        override suspend fun postFormBytes(url: String, formData: Map<String, String>, headers: Map<String, String>, queryParams: Map<String, Any?>, cookies: Map<String, String>):
            Result<ByteArray, ServiceError> = Ok(ByteArray(0))

        override suspend fun getBytes(url: String, headers: Map<String, String>, queryParams: Map<String, Any?>):
            Result<ByteArray, ServiceError> = Ok(ByteArray(0))

        fun close() = Unit
    }

    /** Stands in for the host: turns stored settings plus a secret into the client's config. */
    private fun hostConfigFrom(stored: NetworkConfig, secrets: AppSecretStore): HttpClientConfig =
        runBlocking {
            stored.toHttpClientConfig(proxyPassword = secrets.get(NetworkConfig.proxyPasswordKey))
        }

    private fun tempDir() = Files.createTempDirectory("qtranslate-wiring-test").toFile()

    @Test
    fun `settings the user saved reach the client a plugin is handed`() {
        val stored = NetworkConfig(
            proxyEnabled = true,
            proxyUrl = "http://proxy.example:3128",
            proxyUsername = "alice",
            requestTimeoutSeconds = 90,
            connectTimeoutSeconds = 7,
            socketTimeoutSeconds = 45,
            retryEnabled = false,
            maxRetries = 5,
            maxConnectionsPerHost = 3,
            maxConnectionsTotal = 24,
            hostTimeoutSeconds = mapOf("127.0.0.1" to 300)
        )
        val secrets = AppSecretStore(PluginKeyValueStore(tempDir()))
        runBlocking { secrets.put(NetworkConfig.proxyPasswordKey, "s3cret") }

        var seen: HttpClientConfig? = null
        ScopedPluginContext(
            pluginId = "test-plugin",
            appDataDirectory = tempDir(),
            pluginKeyValueStore = PluginKeyValueStore(tempDir()),
            notificationBus = NotificationBus(),
            textResolver = NoTextResolver,
            logger = SilentLogger,
            httpConfig = hostConfigFrom(stored, secrets),
            httpFactory = { _, config -> seen = config; NoopClient() }
        )

        val config = assertNotNull(seen, "the context never built a client")
        assertEquals(90_000, config.requestTimeoutMillis)
        assertEquals(7_000, config.connectTimeoutMillis)
        assertEquals(45_000, config.socketTimeoutMillis)
        assertEquals(false, config.enableRetry)
        assertEquals(5, config.maxRetries)
        assertEquals(3, config.maxConnectionsPerHost)
        assertEquals(24, config.maxConnectionsTotal)
        assertEquals(300_000, config.hostTimeouts.getValue("127.0.0.1").requestTimeoutMillis)

        // The password never travels through the configuration, so this is the one place that can
        // prove it arrived at the client from the secret store instead.
        assertEquals("http://proxy.example:3128", config.proxy?.url)
        assertEquals("alice", config.proxy?.username)
        assertEquals("s3cret", config.proxy?.password)
    }

    @Test
    fun `a plugin gets no proxy when the user has not enabled one`() {
        val secrets = AppSecretStore(PluginKeyValueStore(tempDir()))
        runBlocking { secrets.put(NetworkConfig.proxyPasswordKey, "left-over") }

        var seen: HttpClientConfig? = null
        ScopedPluginContext(
            pluginId = "test-plugin",
            appDataDirectory = tempDir(),
            pluginKeyValueStore = PluginKeyValueStore(tempDir()),
            notificationBus = NotificationBus(),
            textResolver = NoTextResolver,
            logger = SilentLogger,
            httpConfig = hostConfigFrom(NetworkConfig(proxyEnabled = false), secrets),
            httpFactory = { _, config -> seen = config; NoopClient() }
        )

        // A password remembered from a proxy that is switched off must not resurrect it.
        assertNull(seen?.proxy)
    }

    @Test
    fun `an empty password is stored as absent rather than as blank`() {
        val secrets = AppSecretStore(PluginKeyValueStore(tempDir()))
        runBlocking {
            secrets.put(NetworkConfig.proxyPasswordKey, "temporary")
            secrets.put(NetworkConfig.proxyPasswordKey, "")
        }

        // Blank would read back as a password that is set, and send an empty credential rather
        // than none, which is a different thing on the wire.
        assertNull(runBlocking { secrets.get(NetworkConfig.proxyPasswordKey) })
    }

    @Test
    fun `default settings build the client the application would have built anyway`() {
        val secrets = AppSecretStore(PluginKeyValueStore(tempDir()))

        var seen: HttpClientConfig? = null
        ScopedPluginContext(
            pluginId = "test-plugin",
            appDataDirectory = tempDir(),
            pluginKeyValueStore = PluginKeyValueStore(tempDir()),
            notificationBus = NotificationBus(),
            textResolver = NoTextResolver,
            logger = SilentLogger,
            httpConfig = hostConfigFrom(NetworkConfig(), secrets),
            httpFactory = { _, config -> seen = config; NoopClient() }
        )

        assertEquals(HttpClientConfig(), seen, "an untouched settings page must change nothing")
    }
}
