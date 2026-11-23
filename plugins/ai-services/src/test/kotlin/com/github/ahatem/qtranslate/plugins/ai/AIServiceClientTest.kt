package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.ScriptedHttpClient
import com.github.ahatem.qtranslate.plugins.common.unwrap
import com.github.michaelbull.result.Err
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

    /** Profile-based client (the Batch 2 API). */
    private fun client(httpClient: ScriptedHttpClient) =
        AIServiceClient(
            pluginContext = FakePluginContext(httpClient = httpClient),
            httpClient = httpClient
        )

    private fun hostedProfile(
        baseUrl: String = "https://openrouter.ai/api/v1",
        apiKey: String = "test-key-12345",
        model: String = "openrouter/free",
        customHeaders: String = "",
        temperature: Double = 0.3,
        maxTokens: Int = 4096
    ) = AiProfile(
        preset = "openrouter",
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        customHeaders = customHeaders,
        temperature = temperature,
        maxTokens = maxTokens
    )

    private fun failureOf(result: Result<String, ServiceError>): ServiceError =
        result.fold(
            success = { fail("expected a failure, got success: $it") },
            failure = { it }
        )

    @Test
    fun `the connection test sends a real minimal completion, not an empty ping`() = runBlocking {
        val httpClient = ScriptedHttpClient(listOf(Ok(COMPLETION)))

        client(httpClient).validate(hostedProfile()).unwrap()

        assertEquals("https://openrouter.ai/api/v1/chat/completions", httpClient.urls.single())
        val body = httpClient.bodies.single()
        assertTrue(body.contains("\"model\":\"openrouter/free\""), "body was: $body")
        assertTrue(body.contains("\"role\":\"system\""), "body was: $body")
        assertTrue(body.contains("\"role\":\"user\""), "body was: $body")
        assertTrue(body.contains("\"max_completion_tokens\":4096"), "body was: $body")
    }

    @Test
    fun `requests carry the key as a bearer token and JSON content type`() = runBlocking {
        val httpClient = ScriptedHttpClient(listOf(Ok(COMPLETION)))

        client(httpClient).validate(hostedProfile())

        val headers = httpClient.headers.single()
        assertEquals("Bearer test-key-12345", headers["Authorization"])
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun `complete sends the given profile's model, temperature and max tokens`() = runBlocking {
        val httpClient = ScriptedHttpClient(listOf(Ok(COMPLETION_WITH_TEXT)))

        val result = client(httpClient).complete(
            system = "s",
            userContent = "u",
            profile = hostedProfile(model = "per-profile-model", temperature = 0.7, maxTokens = 128)
        )

        result.unwrap().also { assertEquals("Bonjour", it) }
        val body = httpClient.bodies.single()
        assertTrue(body.contains("\"model\":\"per-profile-model\""), "body was: $body")
        assertTrue(body.contains("\"temperature\":0.7"), "body was: $body")
        assertTrue(body.contains("\"max_completion_tokens\":128"), "body was: $body")
    }

    @Test
    fun `a blank key omits the Authorization header entirely`() = runBlocking {
        val httpClient = ScriptedHttpClient(listOf(Ok(COMPLETION)))
        val local = hostedProfile(baseUrl = "http://localhost:11434/v1", apiKey = "")

        client(httpClient).complete("s", "u", local)

        val headers = httpClient.headers.single()
        assertTrue(!headers.containsKey("Authorization"), "headers were: $headers")
        assertEquals("application/json", headers["Content-Type"])
        assertEquals("http://localhost:11434/v1/chat/completions", httpClient.urls.single())
    }

    @Test
    fun `custom headers are merged but cannot override Authorization or Content-Type`() = runBlocking {
        val httpClient = ScriptedHttpClient(listOf(Ok(COMPLETION)))
        val profile = hostedProfile(
            customHeaders = """{"X-Custom":"yes","Authorization":"Bearer evil-override","Content-Type":"text/plain"}"""
        )

        client(httpClient).complete("s", "u", profile)

        val headers = httpClient.headers.single()
        assertEquals("yes", headers["X-Custom"])
        assertEquals("Bearer test-key-12345", headers["Authorization"])
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun `malformed custom headers are skipped without breaking the request`() = runBlocking {
        val httpClient = ScriptedHttpClient(listOf(Ok(COMPLETION)))

        val result = client(httpClient).complete("s", "u", hostedProfile(customHeaders = "{not-json"))

        result.unwrap()
        assertEquals("Bearer test-key-12345", httpClient.headers.single()["Authorization"])
    }

    @Test
    fun `a hosted endpoint without a key fails before any request is made`() = runBlocking {
        val httpClient = ScriptedHttpClient(listOf())

        val result = client(httpClient).validate(hostedProfile(apiKey = ""))

        result.fold(
            success = { fail("a hosted endpoint without a key must not pass") },
            failure = { assertIs<ServiceError.ConfigurationError>(it) }
        )
        assertTrue(httpClient.urls.isEmpty(), "no request should be attempted")
    }

    @Test
    fun `a local endpoint without a key proceeds to request`() = runBlocking {
        val httpClient = ScriptedHttpClient(listOf(Ok(COMPLETION)))
        val local = hostedProfile(baseUrl = "http://localhost:11434/v1", apiKey = "")

        client(httpClient).validate(local).unwrap()
        assertEquals(1, httpClient.urls.size)
    }

    @Test
    fun `translation sends the configured model and returns the translated text`() = runBlocking {
        val httpClient = ScriptedHttpClient(listOf(Ok(COMPLETION_WITH_TEXT)))
        val service = AITranslatorService(
            client = client(httpClient),
            profileProvider = { hostedProfile(model = "google/gemini-2.5-flash") }
        )

        val response = service.translate(
            TranslationRequest(
                text = "Hello",
                sourceLanguage = LanguageCode.ENGLISH,
                targetLanguage = LanguageCode.FRENCH
            )
        )

        response.unwrap().also { assertEquals("Bonjour", it.translatedText) }
        val body = httpClient.bodies.single()
        assertTrue(body.contains("\"model\":\"google/gemini-2.5-flash\""), "body was: $body")
    }

    @Test
    fun `an in-band rate limit error maps to a rate limit failure`() = runBlocking {
        val body = """{"error":{"message":"Rate limit exceeded","code":"rate_limit_exceeded"}}"""
        val httpClient = ScriptedHttpClient(listOf(Ok(body)))

        val result = client(httpClient).complete(system = "s", userContent = "u", profile = hostedProfile())

        val failure = failureOf(result)
        assertIs<ServiceError.RateLimitError>(failure)
        // Unit-producing final statement: a trailing assertIs would make the @Test method
        // non-void, and JUnit 5 silently skips non-void test methods.
        assertEquals("https://openrouter.ai/api/v1/chat/completions", httpClient.urls.single())
    }

    @Test
    fun `a finish reason of length is a truncation error for text and vision`() = runBlocking<Unit> {
        val body = """{"choices":[{"message":{"role":"assistant","content":"partial"},"finish_reason":"length"}]}"""
        val calls = mapOf<String, suspend (AIServiceClient) -> Result<String, ServiceError>>(
            "text" to { it.complete("s", "u", hostedProfile()) },
            "vision" to {
                it.completeWithImage(
                    system = "s",
                    image = ImageData(byteArrayOf(1, 2, 3), "png", 1, 1),
                    userText = "read this",
                    profile = hostedProfile()
                )
            },
        )
        for ((label, call) in calls) {
            val failure = failureOf(call(client(ScriptedHttpClient(listOf(Ok(body))))))

            assertIs<ServiceError.InvalidResponseError>(failure, label)
            assertTrue(failure.message.contains("truncated"), "$label message was: ${failure.message}")
        }
    }

    @Test
    fun `a null finish reason passes through as a normal completion`() = runBlocking<Unit> {
        val body = """{"choices":[{"message":{"role":"assistant","content":"done"},"finish_reason":null}]}"""
        val httpClient = ScriptedHttpClient(listOf(Ok(body)))

        val result = client(httpClient).complete("s", "u", hostedProfile())

        result.unwrap().also { assertEquals("done", it) }
    }

    @Test
    fun `an unusable body is an invalid response error, not an exception`() = runBlocking<Unit> {
        for (body in listOf("not json", """{"choices":[]}""")) {
            val failure = failureOf(client(ScriptedHttpClient(listOf(Ok(body)))).complete("s", "u", hostedProfile()))

            assertIs<ServiceError.InvalidResponseError>(failure, "body: $body")
        }
    }

    @Test
    fun `transport errors pass through untouched`() = runBlocking {
        // The transport owns the mapping of HTTP status to ServiceError; the client must not re-map
        // it. A 404 arrives as an UnknownError and must not become ServiceUnavailable.
        val errors = listOf(
            ServiceError.ServiceUnavailableError("transport says down"),
            ServiceError.RateLimitError("slow down"),
            ServiceError.AuthenticationError("HTTP 401"),
            ServiceError.UnknownError("HTTP 404 Not Found"),
        )
        for (error in errors) {
            val failure = failureOf(client(ScriptedHttpClient(listOf(Err(error)))).complete("s", "u", hostedProfile()))

            assertEquals(error::class, failure::class, "type for ${error.message}")
            assertEquals(error.message, failure.message)
        }
    }

    private companion object {
        val COMPLETION = """{"choices":[{"message":{"role":"assistant","content":"1"}}]}"""
        val COMPLETION_WITH_TEXT = """{"choices":[{"message":{"role":"assistant","content":"Bonjour"}}]}"""
    }
}
