package com.github.ahatem.qtranslate.plugins.libretranslate

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrThrow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a plugin sees across disable and enable, now that the HTTP client belongs to the context.
 *
 * The client used to be rebuilt by the plugin on every `initialize`, which meant a stale one was
 * impossible by construction. The context now keeps a single client for the plugin's whole life,
 * so anything the plugin captured at enable time could outlive the configuration it was captured
 * with. LibreTranslate is the subject because its endpoint is user-configurable, which makes a
 * stale read visible: the request simply goes to the old address.
 */
class LibreTranslateLifecycleTest {

    @Test
    fun `settings changed while disabled are used by the next enable`() = runBlocking {
        val http = RecordingHttpClient()
        val context = FakePluginContext(httpClient = http)
        val plugin = LibreTranslatePlugin()

        plugin.initialize(context)
        plugin.onSettingsChanged(LibreTranslateSettings(instanceUrl = SERVER_A))
        plugin.onEnable()
        plugin.translate()

        assertEquals("$SERVER_A/translate", http.urls.single())

        plugin.onDisable()
        plugin.onSettingsChanged(LibreTranslateSettings(instanceUrl = SERVER_B))
        plugin.onEnable()
        plugin.translate()

        assertEquals(
            "$SERVER_B/translate",
            http.urls.last(),
            "the re-enabled plugin was still talking to the server it was configured with before"
        )
        plugin.shutdown()
    }

    @Test
    fun `settings changed while still enabled take effect without a restart`() = runBlocking {
        // The harder half of the same problem. Re-enabling rebuilds the services, which would hide
        // a service that captured its configuration once; changing settings underneath a running
        // service does not, so this is what actually pins the reading down.
        val http = RecordingHttpClient()
        val plugin = LibreTranslatePlugin()

        plugin.initialize(FakePluginContext(httpClient = http))
        plugin.onSettingsChanged(LibreTranslateSettings(instanceUrl = SERVER_A))
        plugin.onEnable()
        plugin.translate()

        plugin.onSettingsChanged(LibreTranslateSettings(instanceUrl = SERVER_B))
        plugin.translate()

        assertEquals(
            listOf("$SERVER_A/translate", "$SERVER_B/translate"),
            http.urls,
            "the running service kept the endpoint it was built with"
        )
        plugin.shutdown()
    }

    @Test
    fun `a plugin still translates after being disabled and enabled again`() = runBlocking {
        val http = RecordingHttpClient()
        val context = FakePluginContext(httpClient = http)
        val plugin = LibreTranslatePlugin()

        plugin.initialize(context)
        plugin.onSettingsChanged(LibreTranslateSettings(instanceUrl = SERVER_A))
        plugin.onEnable()
        plugin.translate()

        plugin.onDisable()
        plugin.onEnable()
        val response = plugin.translate()

        assertEquals("bonjour", response)
        assertEquals(2, http.urls.size)
        plugin.shutdown()
    }

    @Test
    fun `many disable and enable cycles keep working on one client`() = runBlocking {
        val http = RecordingHttpClient()
        val context = FakePluginContext(httpClient = http)
        val plugin = LibreTranslatePlugin()

        plugin.initialize(context)
        plugin.onSettingsChanged(LibreTranslateSettings(instanceUrl = SERVER_A))

        repeat(50) {
            plugin.onEnable()
            plugin.translate()
            plugin.onDisable()
        }

        assertEquals(50, http.urls.size, "a cycle stopped issuing its request")
        assertTrue(http.urls.all { it == "$SERVER_A/translate" })
        plugin.shutdown()
    }

    @Test
    fun `disabling one plugin leaves another translating`() = runBlocking {
        val firstHttp = RecordingHttpClient()
        val secondHttp = RecordingHttpClient()
        val first = LibreTranslatePlugin()
        val second = LibreTranslatePlugin()

        first.initialize(FakePluginContext(httpClient = firstHttp))
        second.initialize(FakePluginContext(httpClient = secondHttp))
        first.onSettingsChanged(LibreTranslateSettings(instanceUrl = SERVER_A))
        second.onSettingsChanged(LibreTranslateSettings(instanceUrl = SERVER_B))
        first.onEnable()
        second.onEnable()

        first.onDisable()
        first.shutdown()

        assertEquals("bonjour", second.translate())
        assertEquals("$SERVER_B/translate", secondHttp.urls.single())
        second.shutdown()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Runs one translation through whichever translator the plugin currently exposes. */
    private suspend fun LibreTranslatePlugin.translate(): String {
        val translator = getServices().filterIsInstance<Translator>().single()
        return translator.translate(
            TranslationRequest("hello", LanguageCode.ENGLISH, LanguageCode.FRENCH)
        ).getOrThrow { error -> AssertionError("translation failed: ${error.message}") }
            .translatedText
    }

    private class RecordingHttpClient : TextHttpClient() {
        val urls = mutableListOf<String>()

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String?,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> {
            urls += url
            return Ok("""{"translatedText":"bonjour"}""")
        }

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> {
            urls += url
            return Ok("[]")
        }
    }

    private companion object {
        const val SERVER_A = "http://localhost:5000"
        const val SERVER_B = "http://translate.example.internal:9999"
    }
}
