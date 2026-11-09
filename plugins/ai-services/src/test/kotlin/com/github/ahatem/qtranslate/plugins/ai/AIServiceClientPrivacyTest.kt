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
 * Two privacy properties of the AI plugin's diagnostics.
 *
 * The client logs the endpoint it talks to on every request — a URL the user configured, which
 * can carry credentials for self-hosted gateways. And a model that answers with anything the
 * DTO cannot decode used to have its raw output — which is derived from the user's text —
 * pasted into the surfaced error. Both must be metadata-only.
 */
class AIServiceClientPrivacyTest {

    private val sentinelEndpoint = "SENTINEL-ZQ42-ENDPOINT-6b91"
    private val sentinelRaw = "SENTINEL-ZQ42-RAW-2c85"

    private val completion = """{"choices":[{"message":{"role":"assistant","content":"1"}}]}"""

    /** A completion whose content is the sentinel, so the raw output is traceable. */
    private val rawCompletion =
        """{"choices":[{"message":{"role":"assistant","content":"$sentinelRaw"}}]}"""

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

    private fun settings(baseUrl: String = "https://api.example.invalid/v1") =
        AISettings(baseUrl = baseUrl, apiKey = "test-key-12345")

    @Test
    fun `the configured endpoint URL is not logged`() = runBlocking {
        val logger = CapturingLogger()
        val http = ScriptedHttpClient(Ok(completion))
        val client = AIServiceClient(
            pluginContext = FakePluginContext(logger = logger, httpClient = http),
            httpClient = http,
            settings = { settings("https://api.example.invalid/v1/$sentinelEndpoint") }
        )

        client.validate()

        assertTrue(
            http.urls.single().contains(sentinelEndpoint),
            "the request used the sentinel endpoint"
        )
        assertTrue(
            logger.messages.any { it.contains("chat completion") && it.contains("model=") },
            "the request diagnostic keeps model metadata: ${logger.messages}"
        )
        assertClean(logger, sentinelEndpoint)
    }

    @Test
    fun `a malformed translation response does not put the raw model output in the error`() = runBlocking {
        val logger = CapturingLogger()
        val http = ScriptedHttpClient(Ok(rawCompletion))
        val client = AIServiceClient(
            pluginContext = FakePluginContext(logger = logger, httpClient = http),
            httpClient = http,
            settings = { settings() }
        )
        val service = AITranslatorService(client)

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
            httpClient = http,
            settings = { settings() }
        )
        val service = AIDictionaryService(client)

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
            httpClient = http,
            settings = { settings() }
        )
        val service = AIVisionOcrService(client)

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
