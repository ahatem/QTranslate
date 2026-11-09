package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
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
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION)))

        client(httpClient).validate(hostedProfile()).fold(
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

        client(httpClient).validate(hostedProfile(baseUrl = "https://openrouter.ai/api/v1/"))

        assertEquals("https://openrouter.ai/api/v1/chat/completions", httpClient.urls.single())
    }

    @Test
    fun `a base URL already ending in chat completions is not appended twice`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION)))

        client(httpClient).validate(hostedProfile(baseUrl = "https://openrouter.ai/api/v1/chat/completions"))

        assertEquals("https://openrouter.ai/api/v1/chat/completions", httpClient.urls.single())
    }

    @Test
    fun `whitespace around the base URL is trimmed before resolving the endpoint`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION)))

        client(httpClient).validate(hostedProfile(baseUrl = "  https://openrouter.ai/api/v1/  "))

        assertEquals("https://openrouter.ai/api/v1/chat/completions", httpClient.urls.single())
    }

    @Test
    fun `requests carry the key as a bearer token and JSON content type`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION)))

        client(httpClient).validate(hostedProfile())

        val headers = httpClient.headers.single()
        assertEquals("Bearer test-key-12345", headers["Authorization"])
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun `complete sends the given profile's model, temperature and max tokens`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION_WITH_TEXT)))

        val result = client(httpClient).complete(
            system = "s",
            userContent = "u",
            profile = hostedProfile(model = "per-profile-model", temperature = 0.7, maxTokens = 128)
        )

        result.fold(
            success = { assertEquals("Bonjour", it) },
            failure = { fail("complete should pass: ${it.message}") }
        )
        val body = httpClient.bodies.single().orEmpty()
        assertTrue(body.contains("\"model\":\"per-profile-model\""), "body was: $body")
        assertTrue(body.contains("\"temperature\":0.7"), "body was: $body")
        assertTrue(body.contains("\"max_completion_tokens\":128"), "body was: $body")
    }

    @Test
    fun `complete sends the given profile's key as bearer token`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION)))

        client(httpClient).complete("s", "u", hostedProfile(apiKey = "profile-specific-key"))

        assertEquals("Bearer profile-specific-key", httpClient.headers.single()["Authorization"])
    }

    @Test
    fun `a blank key omits the Authorization header entirely`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION)))
        val local = hostedProfile(baseUrl = "http://localhost:11434/v1", apiKey = "")

        client(httpClient).complete("s", "u", local)

        val headers = httpClient.headers.single()
        assertTrue(!headers.containsKey("Authorization"), "headers were: $headers")
        assertEquals("application/json", headers["Content-Type"])
        assertEquals("http://localhost:11434/v1/chat/completions", httpClient.urls.single())
    }

    @Test
    fun `custom headers are merged but cannot override Authorization or Content-Type`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION)))
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
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION)))

        val result = client(httpClient).complete("s", "u", hostedProfile(customHeaders = "{not-json"))

        result.fold(
            success = { },
            failure = { fail("malformed custom headers must not fail the request: ${it.message}") }
        )
        assertEquals("Bearer test-key-12345", httpClient.headers.single()["Authorization"])
    }

    @Test
    fun `a hosted endpoint without a key fails before any request is made`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf())

        val result = client(httpClient).validate(hostedProfile(apiKey = ""))

        result.fold(
            success = { fail("a hosted endpoint without a key must not pass") },
            failure = { assertIs<ServiceError.ConfigurationError>(it) }
        )
        assertTrue(httpClient.urls.isEmpty(), "no request should be attempted")
    }

    @Test
    fun `a local endpoint without a key proceeds to request`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION)))
        val local = hostedProfile(baseUrl = "http://localhost:11434/v1", apiKey = "")

        client(httpClient).validate(local).fold(
            success = { },
            failure = { fail("a local endpoint needs no key: ${it.message}") }
        )
        assertEquals(1, httpClient.urls.size)
    }

    @Test
    fun `translation sends the configured model and returns the translated text`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(COMPLETION_WITH_TEXT)))
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

        val result = client(httpClient).complete(system = "s", userContent = "u", profile = hostedProfile())

        val failure = failureOf(result)
        assertIs<ServiceError.RateLimitError>(failure)
        // Unit-producing final statement: a trailing assertIs would make the @Test method
        // non-void, and JUnit 5 silently skips non-void test methods.
        assertEquals("https://openrouter.ai/api/v1/chat/completions", httpClient.urls.single())
    }

    @Test
    fun `a finish reason of length is a truncation error`() = runBlocking {
        val body = """{"choices":[{"message":{"role":"assistant","content":"partial"},"finish_reason":"length"}]}"""
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(body)))

        val failure = failureOf(client(httpClient).complete("s", "u", hostedProfile()))

        assertIs<ServiceError.InvalidResponseError>(failure)
        assertTrue(failure.message.contains("truncated"), "message was: ${failure.message}")
    }

    @Test
    fun `a vision finish reason of length is a truncation error`() = runBlocking {
        val body = """{"choices":[{"message":{"role":"assistant","content":"partial"},"finish_reason":"length"}]}"""
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(body)))

        val failure = failureOf(
            client(httpClient).completeWithImage(
                system = "s",
                image = ImageData(byteArrayOf(1, 2, 3), "png", 1, 1),
                userText = "read this",
                profile = hostedProfile()
            )
        )

        assertIs<ServiceError.InvalidResponseError>(failure)
        assertTrue(failure.message.contains("truncated"), "message was: ${failure.message}")
    }

    @Test
    fun `a null finish reason passes through as a normal completion`() = runBlocking {
        val body = """{"choices":[{"message":{"role":"assistant","content":"done"},"finish_reason":null}]}"""
        val httpClient = ScriptedHttpClient(mutableListOf(Ok(body)))

        val result = client(httpClient).complete("s", "u", hostedProfile())

        result.fold(
            success = { assertEquals("done", it) },
            failure = { fail("finish_reason=null must pass: ${it.message}") }
        )
    }

    @Test
    fun `a malformed non-JSON body is an invalid response error, not an exception`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok("not json")))

        val failure = failureOf(client(httpClient).complete("s", "u", hostedProfile()))

        assertIs<ServiceError.InvalidResponseError>(failure)
    }

    @Test
    fun `an empty choices list is an invalid response error`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Ok("""{"choices":[]}""")))

        val failure = failureOf(client(httpClient).complete("s", "u", hostedProfile()))

        assertIs<ServiceError.InvalidResponseError>(failure)
    }

    @Test
    fun `a transport ServiceUnavailable error passes through untouched`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Err(ServiceError.ServiceUnavailableError("transport says down"))))

        val failure = failureOf(client(httpClient).complete("s", "u", hostedProfile()))

        assertIs<ServiceError.ServiceUnavailableError>(failure)
        assertEquals("transport says down", failure.message)
    }

    @Test
    fun `a transport RateLimit error passes through untouched`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Err(ServiceError.RateLimitError("slow down"))))

        val failure = failureOf(client(httpClient).complete("s", "u", hostedProfile()))

        assertIs<ServiceError.RateLimitError>(failure)
        assertEquals("slow down", failure.message)
    }

    @Test
    fun `a transport Authentication error passes through untouched`() = runBlocking {
        val httpClient = ScriptedHttpClient(mutableListOf(Err(ServiceError.AuthenticationError("HTTP 401"))))

        val failure = failureOf(client(httpClient).complete("s", "u", hostedProfile()))

        assertIs<ServiceError.AuthenticationError>(failure)
        assertEquals("HTTP 401", failure.message)
    }

    @Test
    fun `a transport 404 error passes through as-is without re-mapping`() = runBlocking {
        // The transport owns the mapping of HTTP status to ServiceError; the
        // client must not re-map it to ServiceUnavailable or add a synthetic
        // body. Script what the transport would produce for a 404 and pin
        // that it arrives verbatim.
        val httpClient = ScriptedHttpClient(mutableListOf(Err(ServiceError.UnknownError("HTTP 404 Not Found"))))

        val failure = failureOf(client(httpClient).complete("s", "u", hostedProfile()))

        assertTrue(
            failure !is ServiceError.ServiceUnavailableError,
            "a transport 404 must not be re-mapped to ServiceUnavailable"
        )
        assertEquals("HTTP 404 Not Found", failure.message)
    }

    private companion object {
        val COMPLETION = """{"choices":[{"message":{"role":"assistant","content":"1"}}]}"""
        val COMPLETION_WITH_TEXT = """{"choices":[{"message":{"role":"assistant","content":"Bonjour"}}]}"""
    }
}
