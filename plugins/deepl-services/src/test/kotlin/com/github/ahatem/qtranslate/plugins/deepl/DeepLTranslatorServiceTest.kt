package com.github.ahatem.qtranslate.plugins.deepl

import com.github.ahatem.qtranslate.plugins.common.FakePluginContext

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.HttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class DeepLTranslatorServiceTest {
    private val request = TranslationRequest(
        text = "Hello",
        sourceLanguage = LanguageCode.AUTO,
        targetLanguage = LanguageCode.FRENCH
    )

    @Test
    fun `uses free web endpoint when API key is blank`() = runBlocking {
        val client = ScriptedHttpClient(mutableListOf(Ok(WEB_SUCCESS)))
        val modes = mutableListOf<DeepLMode>()
        val service = createService(client, DeepLSettings(), modes::add)

        val result = service.translate(request)

        result.fold(
            success = {
                assertEquals("Bonjour", it.translatedText)
                assertEquals(LanguageCode.ENGLISH, it.detectedLanguage)
                assertEquals(emptyList(), it.alternatives)
            },
            failure = { fail(it.message) }
        )
        assertEquals(listOf("https://oneshot-free.www.deepl.com/v1/translate"), client.urls)
        assertEquals(DeepLMode.FREE_WEB, modes.last())
        assertTrue(client.bodies.single().contains("\"target_lang\":\"fr\""))
        assertEquals("None", client.headers.single()["Authorization"])
    }

    @Test
    fun `uses official API automatically when key is present`() = runBlocking {
        val client = ScriptedHttpClient(mutableListOf(Ok(OFFICIAL_SUCCESS)))
        val modes = mutableListOf<DeepLMode>()
        val settings = DeepLSettings(apiKey = "test-key:fx")
        val service = createService(client, settings, modes::add)

        val result = service.translate(request)

        result.fold(
            success = { assertEquals("Bonjour officiel", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(listOf("https://api-free.deepl.com/v2/translate"), client.urls)
        assertEquals("DeepL-Auth-Key test-key:fx", client.headers.single()["Authorization"])
        assertEquals(DeepLMode.OFFICIAL, modes.last())
    }

    @Test
    fun `rejected key falls back once and skips repeated official failures`() = runBlocking {
        val client = ScriptedHttpClient(mutableListOf(
            Err(ServiceError.AuthenticationError("invalid key")),
            Ok(WEB_SUCCESS),
            Ok(WEB_SUCCESS)
        ))
        val context = FakePluginContext()
        val modes = mutableListOf<DeepLMode>()
        val settings = DeepLSettings(apiKey = "expired-key")
        val service = createService(client, settings, modes::add, context)

        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )

        assertEquals(3, client.urls.size)
        assertEquals(1, client.urls.count { it.contains("api.deepl.com") })
        assertEquals(2, client.urls.count { it.contains("oneshot-free.www.deepl.com") })
        assertEquals(1, context.notifications.size)
        assertEquals(DeepLMode.FREE_WEB_AFTER_REJECTION, modes.last())
    }

    @Test
    fun `maps embedded free endpoint rate limit to actionable guidance`() = runBlocking {
        val client = ScriptedHttpClient(mutableListOf(Ok(
            """{"error":{"code":1042911,"message":"Too many requests"}}"""
        )))
        val service = createService(client, DeepLSettings(), {})

        service.translate(request).fold(
            success = { fail("Expected rate limit error") },
            failure = {
                val error = assertIs<ServiceError.RateLimitError>(it)
                assertEquals(
                    "DeepL free endpoint is rate-limited. Add an API key for official access or try again later.",
                    error.message
                )
            }
        )
        Unit
    }

    @Test
    fun `maps web HTTP rate limit to actionable guidance`() = runBlocking {
        val client = ScriptedHttpClient(mutableListOf(Err(
            ServiceError.RateLimitError("generic HTTP error", retryAfterSeconds = 30)
        )))
        val service = createService(client, DeepLSettings(), {})

        service.translate(request).fold(
            success = { fail("Expected rate limit error") },
            failure = {
                val error = assertIs<ServiceError.RateLimitError>(it)
                assertEquals(
                    "DeepL free endpoint is rate-limited. Add an API key for official access or try again later.",
                    error.message
                )
                assertEquals(30, error.retryAfterSeconds)
            }
        )
        Unit
    }

    @Test
    fun `splits long web input without dropping source text`() = runBlocking {
        val source = "word ".repeat(1_300)
        val client = EchoWebHttpClient()
        val service = createService(client, DeepLSettings(), {})

        val result = service.translate(request.copy(text = source))

        result.fold(
            success = { assertEquals(source, it.translatedText) },
            failure = { fail(it.message) }
        )
        assertTrue(client.requestCount > 1)
    }

    private fun createService(
        client: HttpClient,
        settings: DeepLSettings,
        onModeChanged: (DeepLMode) -> Unit,
        context: PluginContext = FakePluginContext()
    ) = DeepLTranslatorService(
        context = context,
        httpClient = client,
        settings = { settings },
        onModeChanged = onModeChanged,
        minimumWebRequestIntervalMillis = 0,
        rateLimitBackoffMillis = 0,
        maxWebRetries = 0
    )

    private companion object {
        const val OFFICIAL_SUCCESS =
            """{"translations":[{"text":"Bonjour officiel","detected_source_language":"EN"}]}"""
        const val WEB_SUCCESS =
            """{"translations":[{"text":"Bonjour","detected_source_language":"EN"}]}"""
    }
}

private class ScriptedHttpClient(
    private val responses: MutableList<Result<String, ServiceError>>
) : HttpClient {
    val urls = mutableListOf<String>()
    val headers = mutableListOf<Map<String, String>>()
    val bodies = mutableListOf<String>()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = next(url, headers, null)

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = next(url, headers, body)

    private fun next(
        url: String,
        requestHeaders: Map<String, String>,
        body: String?
    ): Result<String, ServiceError> {
        urls += url
        headers += requestHeaders
        bodies += body.orEmpty()
        return responses.removeFirst()
    }
}

private class EchoWebHttpClient : HttpClient {
    var requestCount = 0

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = Err(ServiceError.InvalidInputError("Unexpected GET"))

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        requestCount++
        val request = Json.decodeFromString<DeepLTranslateRequest>(body.orEmpty())
        return Ok(Json.encodeToString(DeepLTranslateResponse(
            translations = listOf(DeepLTranslation(
                text = request.text.single(),
                detectedSourceLanguage = "EN"
            ))
        )))
    }
}

