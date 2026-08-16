package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.plugin.text.PluginTextResolver
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The ownership rules for the HTTP client the context hands plugins.
 *
 * These exist because the client used to belong to the plugin, which built one in `initialize` and
 * closed it in `shutdown`. Moving it to the context changed when it is created and destroyed, and
 * the context outlives any single enable cycle. The three things that could go wrong are: building
 * a client per cycle, closing it on disable so the next cycle gets a dead one, and sharing one
 * between plugins so disabling either breaks the other. Each has a test.
 */
class ScopedPluginContextHttpTest {

    @Test
    fun `one client is built per plugin, not per enable cycle`() {
        val factory = CountingFactory()
        val context = context(factory = factory)

        val original = context.http
        repeat(25) {
            context.cancelScope()
            context.resetScope()
        }

        assertEquals(1, factory.built, "a client was built more than once for one plugin")
        assertSame(original, context.http, "the client was swapped during an enable cycle")
    }

    @Test
    fun `disabling does not close the client, so the next cycle gets a working one`() {
        val factory = CountingFactory()
        val context = context(factory = factory)
        val client = context.http as RecordingHttpClient

        // What disable actually does: cancel the scope, then reset it on the way back up.
        context.cancelScope()
        assertFalse(client.closed, "disabling closed the client the next enable cycle needs")

        context.resetScope()
        assertFalse(client.closed)
    }

    @Test
    fun `rapid cycles neither rebuild nor close the client`() {
        val factory = CountingFactory()
        val context = context(factory = factory)
        val client = context.http as RecordingHttpClient

        repeat(200) {
            context.cancelScope()
            context.resetScope()
        }

        assertEquals(1, factory.built, "cycling built extra clients, which would leak connection pools")
        assertFalse(client.closed)
        assertSame(client, context.http)
    }

    @Test
    fun `the client is closed once the plugin is finished with`() {
        val context = context()
        val client = context.http as RecordingHttpClient

        context.closeHttp()

        assertTrue(client.closed, "the pool was left open, keeping its selector threads alive")
    }

    @Test
    fun `each plugin gets its own client, so closing one leaves the other usable`() {
        val store = PluginKeyValueStore(tempDir())
        val first = context(pluginId = "first", store = store)
        val second = context(pluginId = "second", store = store)

        val firstClient = first.http as RecordingHttpClient
        val secondClient = second.http as RecordingHttpClient

        assertFalse(firstClient === secondClient, "two plugins were handed the same client")

        // Whether by disable or by uninstall, nothing that happens to one may reach the other.
        first.cancelScope()
        first.closeHttp()

        assertTrue(firstClient.closed)
        assertFalse(secondClient.closed, "closing one plugin's client closed another plugin's")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun tempDir() = Files.createTempDirectory("qtranslate-context-test").toFile()

    private fun context(
        pluginId: String = "test-plugin",
        store: PluginKeyValueStore = PluginKeyValueStore(tempDir()),
        factory: CountingFactory = CountingFactory()
    ) = ScopedPluginContext(
        pluginId = pluginId,
        appDataDirectory = tempDir(),
        pluginKeyValueStore = store,
        notificationBus = NotificationBus(),
        textResolver = NoTextResolver,
        logger = SilentLogger,
        httpFactory = factory::build
    )

    private class CountingFactory {
        var built = 0
            private set

        fun build(@Suppress("UNUSED_PARAMETER") logger: Logger): HttpClient {
            built++
            return RecordingHttpClient()
        }
    }

    /** Records only what these tests ask about: whether it was closed. */
    private class RecordingHttpClient : HttpClient, Closeable {
        var closed = false
            private set

        override fun close() {
            closed = true
        }

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> = Ok("")

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String?,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> = Ok("")

        override suspend fun getBytes(
            url: String,
            headers: Map<String, String>,
            queryParams: Map<String, Any?>
        ): Result<ByteArray, ServiceError> = Ok(ByteArray(0))

        override suspend fun postForm(
            url: String,
            formData: Map<String, String>,
            headers: Map<String, String>,
            queryParams: Map<String, Any?>,
            cookies: Map<String, String>
        ): Result<String, ServiceError> = Ok("")

        override suspend fun postFormBytes(
            url: String,
            formData: Map<String, String>,
            headers: Map<String, String>,
            queryParams: Map<String, Any?>,
            cookies: Map<String, String>
        ): Result<ByteArray, ServiceError> = Ok(ByteArray(0))
    }

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
}
