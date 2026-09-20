package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class GoogleTranslatorServiceTest {

    private val request = TranslationRequest(
        text = "Hello",
        sourceLanguage = LanguageCode.ENGLISH,
        targetLanguage = LanguageCode.FRENCH
    )

    private fun createService(client: GoogleTestHttpClient) = GoogleTranslatorService(
        FakePluginContext(),
        GoogleSettings(),
        client,
        GoogleLanguageMapper,
        ApiConfig()
    )

    @Test
    fun `healthy primary is used once and never falls back`() = runBlocking {
        val client = GoogleTestHttpClient(primaryHandler = { Ok(PRIMARY_JSON) })
        val service = createService(client)

        service.translate(request).fold(
            success = {
                assertEquals("Bonjour", it.translatedText)
                assertEquals(LanguageCode.ENGLISH, it.detectedLanguage)
            },
            failure = { fail(it.message) }
        )

        assertEquals(1, client.primaryCalls)
        assertEquals(0, client.fallbackCalls)
    }

    @Test
    fun `fallback parses a nested array response`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("""[["Bonjour","en"]]""") }
        )
        val service = createService(client)

        service.translate(request).fold(
            success = {
                assertEquals("Bonjour", it.translatedText)
                assertEquals(LanguageCode.ENGLISH, it.detectedLanguage)
            },
            failure = { fail(it.message) }
        )
    }

    @Test
    fun `fallback parses a flat array response`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("""["Bonjour","en"]""") }
        )
        val service = createService(client)

        service.translate(request).fold(
            success = {
                assertEquals("Bonjour", it.translatedText)
                assertEquals(LanguageCode.ENGLISH, it.detectedLanguage)
            },
            failure = { fail(it.message) }
        )
    }

    @Test
    fun `fallback response without detected language still succeeds`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("""["Bonjour"]""") }
        )
        val service = createService(client)

        service.translate(request).fold(
            success = {
                assertEquals("Bonjour", it.translatedText)
                assertNull(it.detectedLanguage)
            },
            failure = { fail(it.message) }
        )
    }

    @Test
    fun `empty fallback response fails with an invalid response error`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("[]") }
        )
        val service = createService(client)

        assertIs<ServiceError.InvalidResponseError>(service.translate(request).getError())
        Unit
    }

    @Test
    fun `rate limit on primary opens a cooldown and the next call skips it`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.RateLimitError("rate limited", retryAfterSeconds = 30)) },
            fallbackHandler = { Ok("""["Bonjour"]""") }
        )
        val service = createService(client)

        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )

        assertEquals(1, client.primaryCalls)
        assertEquals(2, client.fallbackCalls)
    }

    @Test
    fun `authentication error is not sent to the fallback`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.AuthenticationError("invalid key")) }
        )
        val service = createService(client)

        assertIs<ServiceError.AuthenticationError>(service.translate(request).getError())
        assertEquals(0, client.fallbackCalls)
    }

    @Test
    fun `a slow primary is abandoned for the fallback`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = {
                kotlinx.coroutines.delay(5_000)
                Ok(PRIMARY_JSON)
            },
            fallbackHandler = { Ok("""["Bonjour"]""") }
        )
        val service = createService(client)

        val startedAt = System.nanoTime()
        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(elapsedMillis < 3_000, "translation took ${elapsedMillis}ms")
        assertEquals(1, client.fallbackCalls)
    }

    private companion object {
        const val PRIMARY_JSON = """{"sentences":[{"trans":"Bonjour","orig":"Hello"}],"src":"en"}"""
    }
}
