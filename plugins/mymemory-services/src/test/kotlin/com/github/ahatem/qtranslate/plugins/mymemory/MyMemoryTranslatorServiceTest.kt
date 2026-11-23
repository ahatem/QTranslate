package com.github.ahatem.qtranslate.plugins.mymemory

import com.github.ahatem.qtranslate.plugins.common.FakePluginContext

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.ScriptedHttpClient
import com.github.ahatem.qtranslate.plugins.common.unwrap
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.fold
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class MyMemoryTranslatorServiceTest {
    @Test
    fun `uses official public endpoint and decodes translated text`() = runBlocking {
        val client = ScriptedHttpClient(
            Ok("""{"responseData":{"translatedText":"Bonjour &amp; bienvenue"},"responseStatus":200}""")
        )
        val service = MyMemoryTranslatorService(TestPluginContext, client, 0)

        val result = service.translate(TranslationRequest(
            text = "Hello & welcome",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.FRENCH
        ))

        result.unwrap().also { assertEquals("Bonjour & bienvenue", it.translatedText) }
        assertEquals("https://api.mymemory.translated.net/get", client.lastUrl)
        assertEquals("en|fr", client.lastQueryParams["langpair"])
        assertEquals("Hello & welcome", client.lastQueryParams["q"])
    }

    @Test
    fun `maps exhausted free quota to a retryable rate limit error`() = runBlocking {
        val client = ScriptedHttpClient(
            Ok("""{"responseData":{"translatedText":""},"responseStatus":429,"quotaFinished":true}""")
        )
        val service = MyMemoryTranslatorService(TestPluginContext, client, 0)

        val result = service.translate(TranslationRequest(
            text = "Hello",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.FRENCH
        ))

        result.fold(
            success = { fail("Expected rate limit error") },
            failure = { assertIs<ServiceError.RateLimitError>(it) }
        )
        Unit
    }

    @Test
    fun `rejects automatic source detection without making a request`() = runBlocking {
        val client = ScriptedHttpClient(Err(ServiceError.NetworkError("should not be called")))
        val service = MyMemoryTranslatorService(TestPluginContext, client, 0)

        val result = service.translate(TranslationRequest(
            text = "Hello",
            sourceLanguage = LanguageCode.AUTO,
            targetLanguage = LanguageCode.FRENCH
        ))

        result.fold(
            success = { fail("Expected unsupported language error") },
            failure = { assertIs<ServiceError.UnsupportedLanguageError>(it) }
        )
        assertEquals(null, client.lastUrl)
    }
}


private val TestPluginContext = FakePluginContext()
