package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins what the shared AI client actually puts on the wire.
 *
 * The plugin talks to whatever OpenAI-compatible endpoint the user configured, OpenRouter by
 * default, and every complaint in the wild about "QTranslate sends the wrong thing" reduces to
 * one of these shapes: the URL the base URL becomes, the model in the body, or the key in the
 * header. All of them are pinned here against scripted responses — nothing in this file reaches
 * a live service.
 */
class AIServiceClientTest {

    private class ScriptedHttpClient(
        private val responses: MutableList<Result<String, ServiceError>>
    ) : TextHttpClient() {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        val bodies = mutableListOf<String?>()

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String?,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> {
            urls += url
            this.headers += headers
            bodies += body
            return responses.removeFirstOrNull()
                ?: error("No scripted response left for POST $url")
        }

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> =
            error("The AI client should not issue GETs, but one went to $url")
    }

    private fun client(httpClient: ScriptedHttpClient, settings: AISettings) =
        AIServiceClient(
            pluginContext = FakePluginContext(httpClient = httpClient),
            httpClient = httpClient,
            settings = { settings }
        )

    private fun hostedSettings(baseUrl: String = "https://openrouter.ai/api/v1") =
        AISettings(baseUrl = baseUrl, apiKey = "test-key-12345")

    @Test
    fun `the connection test sends a real minimal completion, not an empty ping`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION)))
        val settings = hostedSettings()

        client(httpClient, settings).validate().fold(
            success = { },
            failure = { fail("validate should pass against a working endpoint: ${it.message}") }
        )

        assertEquals("https://openrouter.ai/api/v1/chat/completions", httpClient.urls.single())
        val body = httpClient.bodies.single().orEmpty()
        assertTrue(body.contains("\"model\":\"openrouter/free\""), "body was: $body")
        assertTrue(body.contains("\"role\":\"system\""), "body was: $body")
        assertTrue(body.contains("\"role\":\"user\""), "body was: $body")
        assertTrue(body.contains("\"max_completion_tokens\":4096"), "body was: $body")
    }

    @Test
    fun `a trailing slash on the base URL does not split the endpoint`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION)))

        client(httpClient, hostedSettings(baseUrl = "https://openrouter.ai/api/v1/")).validate()

        assertEquals("https://openrouter.ai/api/v1/chat/completions", httpClient.urls.single())
    }

    @Test
    fun `requests carry the key as a bearer token and JSON content type`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION)))

        client(httpClient, hostedSettings()).validate()

        val headers = httpClient.headers.single()
        assertEquals("Bearer test-key-12345", headers["Authorization"])
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun `a hosted endpoint without a key fails before any request is made`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf())

        val result = client(httpClient, AISettings()).validate()

        result.fold(
            success = { fail("a hosted endpoint without a key must not pass") },
            failure = { assertIs<ServiceError.ConfigurationError>(it) }
        )
        assertTrue(httpClient.urls.isEmpty(), "no request should be attempted")
    }

    @Test
    fun `translation sends the configured model and returns the translated text`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION_WITH_TEXT)))
        val settings = hostedSettings().copy(model = "google/gemini-2.5-flash")
        val service = AITranslatorService(client = client(httpClient, settings))

        val response = service.translate(
            TranslationRequest(
                text = "Hello",
                sourceLanguage = LanguageCode.ENGLISH,
                targetLanguage = LanguageCode.FRENCH
            )
        )

        response.fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail("translation against a scripted success should pass: ${it.message}") }
        )
        val body = httpClient.bodies.single().orEmpty()
        assertTrue(body.contains("\"model\":\"google/gemini-2.5-flash\""), "body was: $body")
    }

    @Test
    fun `an in-band rate limit error maps to a rate limit failure`() = runBlocking {
        val body = """{"error":{"message":"Rate limit exceeded","code":"rate_limit_exceeded"}}"""
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(body)))

        val result = client(httpClient, hostedSettings()).complete(system = "s", userContent = "u")

        val failure = result.fold(
            success = { fail("a rate-limited answer must not parse as a completion") },
            failure = { it }
        )
        assertIs<ServiceError.RateLimitError>(failure)
        // Unit-producing final statement: a trailing assertIs would make the @Test method
        // non-void, and JUnit 5 silently skips non-void test methods.
        assertEquals("https://openrouter.ai/api/v1/chat/completions", httpClient.urls.single())
    }

    private companion object {
        val COMPLETION = """{"choices":[{"message":{"role":"assistant","content":"1"}}]}"""
        val COMPLETION_WITH_TEXT = """{"choices":[{"message":{"role":"assistant","content":"Bonjour"}}]}"""
    }
}
