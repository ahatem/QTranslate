package com.github.ahatem.qtranslate.plugins.yandexweb

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.plugins.common.ScriptedHttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrThrow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class YandexWebTranslatorServiceTest {
    @Test
    fun `translates in both directions, detecting the source only for auto`() = runBlocking {
        // (source, target, provider reply) -> expected text, detected language, form field
        val cases = listOf(
            Triple(Triple("مرحبا بالعالم", LanguageCode.AUTO, LanguageCode.ENGLISH),
                """[{"text":"Hello World","from":"ar","to":"en"}]""",
                Triple("Hello World", LanguageCode.ARABIC, "statLang=en")),
            Triple(Triple("Hello world", LanguageCode.ENGLISH, LanguageCode.ARABIC),
                """[{"text":"مرحبا العالم","from":"en","to":"ar"}]""",
                Triple("مرحبا العالم", null, "locale=ar")),
        )
        for ((input, reply, expected) in cases) {
            val transport = ScriptedHttpClient(Ok(reply))

            val response = service(transport).translate(
                TranslationRequest(input.first, input.second, input.third)
            ).getOrThrow { IllegalStateException(it.message, it.cause) }

            assertEquals(expected.first, response.translatedText, input.first)
            assertEquals(expected.second, response.detectedLanguage, input.first)
            assertTrue(transport.lastBody.orEmpty().contains(expected.third), input.first)
        }
    }

    @Test
    fun `maps rate limits to an actionable unofficial endpoint message`() = runBlocking {
        val transport = ScriptedHttpClient(Err(ServiceError.RateLimitError("generic", 12)))

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
        val transport = ScriptedHttpClient(Ok("<html>blocked</html>"))

        val error = service(transport).translate(
            TranslationRequest("Hello", LanguageCode.ENGLISH, LanguageCode.ARABIC)
        ).getError()

        assertIs<ServiceError.InvalidResponseError>(error)
        assertTrue(error.message.contains("endpoint may have changed"))
    }

    @Test
    fun `falls back to Mozhi Yandex when the direct endpoint changes`() = runBlocking {
        val transport = ScriptedHttpClient { request ->
            if (request.method == "GET") Ok("""{"translated-text":"مرحبا بالعالم","source_language":"en"}""")
            else Ok("<html>blocked</html>")
        }

        val response = service(transport).translate(
            TranslationRequest("Hello world", LanguageCode.AUTO, LanguageCode.ARABIC)
        ).getOrThrow { IllegalStateException(it.message, it.cause) }

        assertEquals("مرحبا بالعالم", response.translatedText)
        assertEquals(LanguageCode.ENGLISH, response.detectedLanguage)
        assertTrue(transport.lastUrl.orEmpty().endsWith("/api/translate"))
        assertEquals("yandex", transport.lastQueryParams["engine"])
        assertEquals("ar", transport.lastQueryParams["to"])
    }

    @Test
    fun `rejects unsupported languages before making a request`() = runBlocking {
        val transport = ScriptedHttpClient(Ok("[]"))

        val error = service(transport).translate(
            TranslationRequest("Hello", LanguageCode("zu"), LanguageCode.ARABIC)
        ).getError()

        assertIs<ServiceError.UnsupportedLanguageError>(error)
        assertTrue(transport.requests.isEmpty(), "no request should be made")
    }

    @Test
    fun `rejects text beyond the provider limit before making a request`() = runBlocking {
        val transport = ScriptedHttpClient(Ok("[]"))

        val error = service(transport).translate(
            TranslationRequest("a".repeat(5_001), LanguageCode.ENGLISH, LanguageCode.ARABIC)
        ).getError()

        assertIs<ServiceError.InvalidInputError>(error)
        assertTrue(transport.requests.isEmpty(), "no request should be made")
    }

    @Test
    fun `serializes requests and enforces the minimum interval`() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val transport = ScriptedHttpClient(Ok("""[{"text":"ok","from":"en","to":"ar"}]"""))
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

}
