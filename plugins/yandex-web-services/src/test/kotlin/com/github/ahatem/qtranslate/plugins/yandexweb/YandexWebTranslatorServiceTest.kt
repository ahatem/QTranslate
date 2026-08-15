package com.github.ahatem.qtranslate.plugins.yandexweb

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.HttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrThrow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class YandexWebTranslatorServiceTest {
    @Test
    fun `translates Arabic to English and reports detected language`() = runBlocking {
        val transport = FakeHttpClient(Ok("""[{"text":"Hello World","from":"ar","to":"en"}]"""))
        val service = service(transport)

        val response = service.translate(
            TranslationRequest("مرحبا بالعالم", LanguageCode.AUTO, LanguageCode.ENGLISH)
        ).getOrThrow { IllegalStateException(it.message, it.cause) }

        assertEquals("Hello World", response.translatedText)
        assertEquals(LanguageCode.ARABIC, response.detectedLanguage)
        assertTrue(transport.lastBody.orEmpty().contains("statLang=en"))
    }

    @Test
    fun `translates English to Arabic`() = runBlocking {
        val transport = FakeHttpClient(Ok("""[{"text":"مرحبا العالم","from":"en","to":"ar"}]"""))

        val response = service(transport).translate(
            TranslationRequest("Hello world", LanguageCode.ENGLISH, LanguageCode.ARABIC)
        ).getOrThrow { IllegalStateException(it.message, it.cause) }

        assertEquals("مرحبا العالم", response.translatedText)
        assertEquals(null, response.detectedLanguage)
        assertTrue(transport.lastBody.orEmpty().contains("locale=ar"))
    }

    @Test
    fun `maps rate limits to an actionable unofficial endpoint message`() = runBlocking {
        val transport = FakeHttpClient(Err(ServiceError.RateLimitError("generic", 12)))

        val error = service(transport).translate(
            TranslationRequest("Hello", LanguageCode.ENGLISH, LanguageCode.ARABIC)
        ).getError()

        assertIs<ServiceError.RateLimitError>(error)
        assertEquals(12, error.retryAfterSeconds)
        assertTrue(error.message.contains("unofficial free endpoint"))
        assertTrue(error.message.contains("try again later"))
    }

    @Test
    fun `reports an endpoint contract change for malformed responses`() = runBlocking {
        val transport = FakeHttpClient(Ok("<html>blocked</html>"))

        val error = service(transport).translate(
            TranslationRequest("Hello", LanguageCode.ENGLISH, LanguageCode.ARABIC)
        ).getError()

        assertIs<ServiceError.InvalidResponseError>(error)
        assertTrue(error.message.contains("endpoint may have changed"))
    }

    @Test
    fun `falls back to Mozhi Yandex when the direct endpoint changes`() = runBlocking {
        val transport = FakeHttpClient(
            result = Ok("<html>blocked</html>"),
            getResult = Ok("""{"translated-text":"مرحبا بالعالم","source_language":"en"}""")
        )

        val response = service(transport).translate(
            TranslationRequest("Hello world", LanguageCode.AUTO, LanguageCode.ARABIC)
        ).getOrThrow { IllegalStateException(it.message, it.cause) }

        assertEquals("مرحبا بالعالم", response.translatedText)
        assertEquals(LanguageCode.ENGLISH, response.detectedLanguage)
        assertTrue(transport.lastGetUrl.orEmpty().endsWith("/api/translate"))
        assertEquals("yandex", transport.lastQueryParams["engine"])
        assertEquals("ar", transport.lastQueryParams["to"])
    }

    @Test
    fun `rejects unsupported languages before making a request`() = runBlocking {
        val transport = FakeHttpClient(Ok("[]"))

        val error = service(transport).translate(
            TranslationRequest("Hello", LanguageCode("zu"), LanguageCode.ARABIC)
        ).getError()

        assertIs<ServiceError.UnsupportedLanguageError>(error)
        assertEquals(null, transport.lastBody)
    }

    @Test
    fun `rejects text beyond the provider limit before making a request`() = runBlocking {
        val transport = FakeHttpClient(Ok("[]"))

        val error = service(transport).translate(
            TranslationRequest("a".repeat(5_001), LanguageCode.ENGLISH, LanguageCode.ARABIC)
        ).getError()

        assertIs<ServiceError.InvalidInputError>(error)
        assertEquals(null, transport.lastBody)
    }

    @Test
    fun `serializes requests and enforces the minimum interval`() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val transport = FakeHttpClient(Ok("""[{"text":"ok","from":"en","to":"ar"}]"""))
        val client = YandexWebClient(
            httpClient = transport,
            minimumIntervalMillis = 750,
            clockMillis = { now },
            wait = { millis -> waits += millis; now += millis }
        )

        client.translate("one", "ar")
        client.translate("two", "ar")

        assertEquals(listOf(750L), waits)
    }

    private fun service(httpClient: HttpClient) =
        YandexWebTranslatorService(YandexWebClient(httpClient, minimumIntervalMillis = 0))

    private class FakeHttpClient(
        private val result: Result<String, ServiceError>,
        private val getResult: Result<String, ServiceError> = result
    ) : HttpClient {
        var lastBody: String? = null
        var lastGetUrl: String? = null
        var lastQueryParams: Map<String, Any?> = emptyMap()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> {
            lastGetUrl = url
            lastQueryParams = queryParams
            return getResult
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String?,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> {
            lastBody = body
            return result
        }
    }
}
