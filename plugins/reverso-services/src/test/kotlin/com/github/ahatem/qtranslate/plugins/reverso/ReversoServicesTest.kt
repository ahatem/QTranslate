package com.github.ahatem.qtranslate.plugins.reverso

import com.github.ahatem.qtranslate.plugins.common.FakePluginContext

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.BilingualDictionaryRequest
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
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

class ReversoServicesTest {
    @Test
    fun `plugin exposes translation and dictionary as distinct services`() = runBlocking {
        val plugin = ReversoPlugin()
        plugin.initialize(TestPluginContext)
        plugin.onEnable()

        assertEquals(
            listOf("Reverso Translation", "Reverso Dictionary"),
            plugin.getServices().map { it.name }
        )
        // Keys, not ids: the plugin declares a name unique within itself and the host composes
        // the identifier the rest of the application sees.
        assertEquals(
            listOf("reverso-services-translation", "reverso-services-dictionary"),
            plugin.getServices().map { it.key }
        )
        plugin.onDisable()
        plugin.shutdown()
    }

    @Test
    fun `translation uses current free endpoint and selected language pair`() = runBlocking {
        val http = RecordingHttpClient(Ok(TEXT_RESPONSE))
        val service = ReversoTranslatorService(client(http))

        val result = service.translate(TranslationRequest(
            text = "The bank is closed.",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.FRENCH
        ))

        result.fold(
            success = { assertEquals("La banque est fermee.", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals("https://cps.reverso.net/api2/TranslateText", http.lastUrl)
        val body = Json.decodeFromString<ReversoTextRequest>(http.lastBody)
        assertEquals("en-fr", body.direction)
        assertEquals("chromeextension", body.origin)
    }

    @Test
    fun `dictionary maps ranked translations and bilingual contexts into native entries`() = runBlocking {
        val http = RecordingHttpClient(Ok(WORD_RESPONSE))
        val service = ReversoDictionaryService(client(http))

        val result = service.lookupBilingual(BilingualDictionaryRequest(
            word = "bank",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.FRENCH
        ))

        result.fold(
            success = { response ->
                assertEquals(2, response.entries.size)
                assertEquals("bank", response.entries.first().word)
                assertEquals("noun - banque", response.entries.first().partOfSpeech)
                assertEquals("La banque est fermee.", response.entries.first().definitions.first().text)
                assertEquals("The bank is closed.", response.entries.first().definitions.first().example)
                assertEquals(emptyList(), response.entries.first().synonyms)
                assertEquals("rive", response.entries[1].definitions.single().text)
            },
            failure = { fail(it.message) }
        )
        val body = Json.decodeFromString<ReversoWordRequest>(http.lastBody)
        assertEquals("en-fr", body.direction)
    }

    @Test
    fun `dictionary defaults English lookups to French when target is unavailable`() = runBlocking {
        val http = RecordingHttpClient(Ok(WORD_RESPONSE))
        val service = ReversoDictionaryService(client(http))

        service.lookup(DictionaryRequest("bank", LanguageCode.ENGLISH)).fold(
            success = {},
            failure = { fail(it.message) }
        )

        assertEquals("en-fr", Json.decodeFromString<ReversoWordRequest>(http.lastBody).direction)
    }

    @Test
    fun `provider failure has a clear free service message`() = runBlocking {
        val http = RecordingHttpClient(Ok(
            """{"error":true,"success":false,"message":"Too many requests"}"""
        ))
        val service = ReversoTranslatorService(client(http))

        val result = service.translate(TranslationRequest(
            text = "Hello",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.FRENCH
        ))

        result.fold(
            success = { fail("Expected rate limit error") },
            failure = {
                assertIs<ServiceError.RateLimitError>(it)
                assertTrue(it.message.contains("free service"))
            }
        )
        Unit
    }

    @Test
    fun `translation rejects auto detection without making a request`() = runBlocking {
        val http = RecordingHttpClient(Err(ServiceError.NetworkError("must not be called")))
        val service = ReversoTranslatorService(client(http))

        val result = service.translate(TranslationRequest(
            text = "Hello",
            sourceLanguage = LanguageCode.AUTO,
            targetLanguage = LanguageCode.FRENCH
        ))

        result.fold(
            success = { fail("Expected unsupported language error") },
            failure = { assertIs<ServiceError.UnsupportedLanguageError>(it) }
        )
        assertEquals(null, http.lastUrl)
    }

    private fun client(httpClient: HttpClient) = ReversoClient(
        context = TestPluginContext,
        httpClient = httpClient,
        apiConfig = ApiConfig(),
        minimumRequestIntervalMillis = 0
    )

    private companion object {
        const val TEXT_RESPONSE =
            """{"error":false,"success":true,"translation":"La banque est fermee.","directionFrom":"en","directionTo":"fr"}"""
        const val WORD_RESPONSE = """
            {
              "error": false,
              "success": true,
              "sources": [{
                "source": "bank",
                "displaySource": "bank",
                "translations": [
                  {
                    "translation": "banque",
                    "pos": "nf",
                    "contexts": [{
                      "source": "The <em>bank</em> is closed.",
                      "target": "La <em>banque</em> est fermee.",
                      "isGood": true
                    }]
                  },
                  {"translation": "rive", "pos": "nf", "contexts": []}
                ]
              }]
            }
        """
    }
}

private class RecordingHttpClient(
    private val response: Result<String, ServiceError>
) : TextHttpClient() {
    var lastUrl: String? = null
    var lastBody: String = ""

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = response

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        lastUrl = url
        lastBody = body.orEmpty()
        return response
    }
}


private val TestPluginContext = FakePluginContext()
