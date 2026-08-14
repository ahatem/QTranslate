package com.github.ahatem.qtranslate.plugins.mymemory

import com.github.ahatem.qtranslate.plugins.common.FakePluginContext

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.HttpClient
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

class MyMemoryTranslatorServiceTest {
    @Test
    fun `uses official public endpoint and decodes translated text`() = runBlocking {
        val client = RecordingHttpClient(
            Ok("""{"responseData":{"translatedText":"Bonjour &amp; bienvenue"},"responseStatus":200}""")
        )
        val service = MyMemoryTranslatorService(TestPluginContext, client, ApiConfig(), 0)

        val result = service.translate(TranslationRequest(
            text = "Hello & welcome",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.FRENCH
        ))

        result.fold(
            success = { assertEquals("Bonjour & bienvenue", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals("https://api.mymemory.translated.net/get", client.lastUrl)
        assertEquals("en|fr", client.lastQueryParams["langpair"])
        assertEquals("Hello & welcome", client.lastQueryParams["q"])
    }

    @Test
    fun `maps exhausted free quota to a retryable rate limit error`() = runBlocking {
        val client = RecordingHttpClient(
            Ok("""{"responseData":{"translatedText":""},"responseStatus":429,"quotaFinished":true}""")
        )
        val service = MyMemoryTranslatorService(TestPluginContext, client, ApiConfig(), 0)

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
        val client = RecordingHttpClient(Err(ServiceError.NetworkError("should not be called")))
        val service = MyMemoryTranslatorService(TestPluginContext, client, ApiConfig(), 0)

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

private class RecordingHttpClient(
    private val response: Result<String, ServiceError>
) : HttpClient {
    var lastUrl: String? = null
    var lastQueryParams: Map<String, Any?> = emptyMap()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        lastUrl = url
        lastQueryParams = queryParams
        return response
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = response
}


private val TestPluginContext = FakePluginContext()
