package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.CapturingLogger
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Privacy properties of the AI plugin's diagnostics.
 *
 * The client logs only model metadata on every request — never the endpoint
 * URL (which can carry credentials for self-hosted gateways), the API key,
 * `Authorization`/header values, request bodies, or raw response bodies
 * (derived from the user's text). Surfaced errors must likewise stay
 * metadata-only. All sentinels are synthetic.
 */
class AIServiceClientPrivacyTest {

    private val sentinelEndpoint = "SENTINEL-ZQ42-ENDPOINT-6b91"
    private val sentinelRaw = "SENTINEL-ZQ42-RAW-2c85"
    private val sentinelKey = "SENTINEL-ZQ42-KEY-9f31"
    private val sentinelHeaderValue = "SENTINEL-ZQ42-HEADER-4d20"

    private val completion = """{"choices":[{"message":{"role":"assistant","content":"1"}}]}"""

    /** A completion whose content is the sentinel, so the raw output is traceable. */
    private val rawCompletion =
        """{"choices":[{"message":{"role":"assistant","content":"$sentinelRaw"}}]}"""

    /** A truncated completion whose content is the sentinel. */
    private val truncatedRawCompletion =
        """{"choices":[{"message":{"role":"assistant","content":"$sentinelRaw"},"finish_reason":"length"}]}"""

    private class ScriptedHttpClient(
        private val response: Result<String, ServiceError>
    ) : TextHttpClient() {
        val urls = mutableListOf<String>()

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String?,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> {
            urls += url
            return response
        }

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> =
            error("The AI client should not issue GETs, but one went to $url")
    }

    private fun profile(
        baseUrl: String = "https://api.example.invalid/v1",
        customHeaders: String = ""
    ) = AiProfile(
        preset = "test",
        baseUrl = baseUrl,
        apiKey = sentinelKey,
        model = "test-model",
        customHeaders = customHeaders,
        temperature = 0.3,
        maxTokens = 4096
    )

    @Test
    fun `the configured endpoint URL is not logged`() = runBlocking {
        val logger = CapturingLogger()
        val http = ScriptedHttpClient(Ok(completion))
        val client = AIServiceClient(
            pluginContext = FakePluginContext(logger = logger, httpClient = http),
            httpClient = http
        )

        client.validate(profile(baseUrl = "https://api.example.invalid/v1/$sentinelEndpoint"))

        assertTrue(
            http.urls.single().contains(sentinelEndpoint),
            "the request used the sentinel endpoint"
        )
        assertTrue(
            logger.messages.any { it.contains("chat completion") && it.contains("model=") },
            "the request diagnostic keeps model metadata: ${logger.messages}"
        )
        assertClean(logger, sentinelEndpoint)
        assertClean(logger, sentinelKey)
    }

    @Test
    fun `logs never contain the API key or the bearer value`() = runBlocking {
        val logger = CapturingLogger()
        val http = ScriptedHttpClient(Ok(completion))
        val client = AIServiceClient(
            pluginContext = FakePluginContext(logger = logger, httpClient = http),
            httpClient = http
        )

        client.complete(system = "sys", userContent = "user", profile = profile())

        assertClean(logger, sentinelKey, "Bearer $sentinelKey")
    }

    @Test
    fun `a truncated completion error contains neither the raw body nor the key`() = runBlocking {
        val logger = CapturingLogger()
        val http = ScriptedHttpClient(Ok(truncatedRawCompletion))
        val client = AIServiceClient(
            pluginContext = FakePluginContext(logger = logger, httpClient = http),
            httpClient = http
        )

        val error = client.complete(system = "s", userContent = "u", profile = profile()).fold(
            success = { fail("a truncated completion must fail") },
            failure = { it }
        )

        assertFalse(error.message.contains(sentinelRaw), "error must not carry the raw output: ${error.message}")
        assertFalse(error.message.contains(sentinelKey), "error must not carry the key: ${error.message}")
        assertContains(error.message, "truncated")
        assertClean(logger, sentinelRaw, sentinelKey)
    }

    @Test
    fun `malformed custom header values are not logged when the parse warning fires`() = runBlocking {
        val logger = CapturingLogger()
        val http = ScriptedHttpClient(Ok(completion))
        val client = AIServiceClient(
            pluginContext = FakePluginContext(logger = logger, httpClient = http),
            httpClient = http
        )

        // Broken JSON carrying a secret-looking value: the warning must name
        // the failure without echoing the header blob.
        val brokenHeaders = """{"X-Api-Secret":"$sentinelHeaderValue","""
        client.complete(system = "s", userContent = "u", profile = profile(customHeaders = brokenHeaders))

        assertTrue(
            logger.messages.any { it.contains("Custom Headers") },
            "the parse warning should have fired: ${logger.messages}"
        )
        assertClean(logger, sentinelHeaderValue)
    }

    @Test
    fun `a transport error surfaces without the key or body`() = runBlocking {
        val logger = CapturingLogger()
        val http = ScriptedHttpClient(Err(ServiceError.AuthenticationError("HTTP 401 Unauthorized")))
        val client = AIServiceClient(
            pluginContext = FakePluginContext(logger = logger, httpClient = http),
            httpClient = http
        )

        val error = client.complete(system = "s", userContent = "u", profile = profile()).fold(
            success = { fail("a transport auth error must fail") },
            failure = { it }
        )

        assertFalse(error.message.contains(sentinelKey), "error must not carry the key: ${error.message}")
        assertFalse(error.message.contains("Bearer"), "error must not carry credentials: ${error.message}")
        assertClean(logger, sentinelKey)
    }

    @Test
    fun `a malformed translation response does not put the raw model output in the error`() = runBlocking {
        val logger = CapturingLogger()
        val http = ScriptedHttpClient(Ok(rawCompletion))
        val client = AIServiceClient(
            pluginContext = FakePluginContext(logger = logger, httpClient = http),
            httpClient = http
        )
        val service = AITranslatorService(client, profileProvider = { profile() })

        val result = service.translate(
            TranslationRequest("hello", LanguageCode.AUTO, LanguageCode.FRENCH)
        )

        val error = result.fold(
            success = { fail("a response the DTO cannot decode must fail") },
            failure = { it }
        )
        assertFalse(error.message.contains(sentinelRaw), "the surfaced error must not carry the raw output")
        assertContains(error.message, "Failed to parse AI translation response")
        assertClean(logger, sentinelRaw)
    }

    @Test
    fun `a malformed dictionary response does not put the raw model output in the error`() = runBlocking {
        val logger = CapturingLogger()
        val http = ScriptedHttpClient(Ok(rawCompletion))
        val client = AIServiceClient(
            pluginContext = FakePluginContext(logger = logger, httpClient = http),
            httpClient = http
        )
        val service = AIDictionaryService(client, profileProvider = { profile() })

        val result = service.lookup(DictionaryRequest("word", LanguageCode.ENGLISH))

        val error = result.fold(
            success = { fail("a response the DTO cannot decode must fail") },
            failure = { it }
        )
        assertFalse(error.message.contains(sentinelRaw), "the surfaced error must not carry the raw output")
        assertContains(error.message, "Failed to parse AI dictionary response")
        assertClean(logger, sentinelRaw)
    }

    @Test
    fun `a malformed vision response does not put the raw model output in the error`() = runBlocking {
        val logger = CapturingLogger()
        val http = ScriptedHttpClient(Ok(rawCompletion))
        val client = AIServiceClient(
            pluginContext = FakePluginContext(logger = logger, httpClient = http),
            httpClient = http
        )
        val service = AIVisionOcrService(client, profileProvider = { profile() })

        val result = service.extractText(
            OCRRequest(ImageData(byteArrayOf(1, 2, 3), "png", 1, 1), LanguageCode.ENGLISH)
        )

        val error = result.fold(
            success = { fail("a response the DTO cannot decode must fail") },
            failure = { it }
        )
        assertFalse(error.message.contains(sentinelRaw), "the surfaced error must not carry the raw output")
        assertContains(error.message, "Failed to parse AI OCR response")
        assertClean(logger, sentinelRaw)
    }

    private fun assertClean(logger: CapturingLogger, vararg sentinels: String) {
        val captured = logger.messages + logger.loggedThrowables
        val leaked = captured.filter { line -> sentinels.any { line.contains(it) } }
        assertTrue(leaked.isEmpty(), "sentinel content leaked into logs: $leaked")
    }
}
