package com.github.ahatem.qtranslate.plugins.wikimedia

import com.github.ahatem.qtranslate.plugins.common.FakePluginContext

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
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

class WikimediaReferenceTest {
    @Test
    fun `Wikipedia returns clean reference fields from official search JSON`() = runBlocking {
        val transport = QueueHttpClient(listOf(
            Ok("""{"pages":[{"key":"Earth","title":"Earth","excerpt":"<span class=\"searchmatch\">Earth</span> is the third planet.","description":"Third planet from the Sun"}]}""")
        ))

        val response = WikipediaService(client(transport)).lookup(
            DictionaryRequest("earth", LanguageCode.ENGLISH)
        ).getOrThrow(::failure)

        assertEquals("Earth", response.entries.single().word)
        assertEquals("encyclopedia", response.entries.single().partOfSpeech)
        assertEquals(
            listOf("Third planet from the Sun", "Earth is the third planet."),
            response.entries.single().definitions.map { it.text }
        )
        assertTrue(transport.urls.single().contains("en.wikipedia.org/w/rest.php/v1/search/page"))
    }

    @Test
    fun `Wiktionary returns separate parts of speech from official page JSON`() = runBlocking {
        val transport = QueueHttpClient(listOf(
            Ok("""{"pages":[{"key":"hello","title":"hello","excerpt":"hello"}]}"""),
            Ok(pageJson("hello", ENGLISH_WIKTIONARY_HTML))
        ))

        val response = WiktionaryService(client(transport)).lookup(
            DictionaryRequest("hello", LanguageCode.ENGLISH)
        ).getOrThrow(::failure)

        assertEquals(listOf("Interjection", "Noun"), response.entries.map { it.partOfSpeech })
        assertEquals("A greeting said when meeting someone.", response.entries.first().definitions.first().text)
        assertTrue(transport.urls.last().contains("en.wiktionary.org/w/rest.php/v1/page/hello/with_html"))
    }

    @Test
    fun `Wiktionary parses Arabic definitions from the Arabic edition`() = runBlocking {
        val transport = QueueHttpClient(listOf(
            Ok("""{"pages":[{"key":"مرحبا","title":"مرحبا","excerpt":"مرحبا"}]}"""),
            Ok(pageJson("مرحبا", ARABIC_WIKTIONARY_HTML))
        ))

        val response = WiktionaryService(client(transport)).lookup(
            DictionaryRequest("مرحبا", LanguageCode.ARABIC)
        ).getOrThrow(::failure)

        assertEquals("المعاني", response.entries.single().partOfSpeech)
        assertEquals(2, response.entries.single().definitions.size)
        assertTrue(response.entries.single().definitions.first().text.contains("تَحِيَّة"))
        assertTrue(transport.urls.first().contains("ar.wiktionary.org"))
    }

    @Test
    fun `missing pages return an empty dictionary response`() = runBlocking {
        val transport = QueueHttpClient(listOf(Ok("""{"pages":[]}""")))

        val response = WikipediaService(client(transport)).lookup(
            DictionaryRequest("not-a-real-page", LanguageCode.ENGLISH)
        ).getOrThrow(::failure)

        assertTrue(response.entries.isEmpty())
    }

    @Test
    fun `rate limits retain retry hints and name the service`() = runBlocking {
        val transport = QueueHttpClient(listOf(Err(ServiceError.RateLimitError("generic", 9))))

        val error = WikipediaService(client(transport)).lookup(
            DictionaryRequest("Earth", LanguageCode.ENGLISH)
        ).getError()

        assertIs<ServiceError.RateLimitError>(error)
        assertEquals(9, error.retryAfterSeconds)
        assertTrue(error.message.contains("Wikipedia"))
    }

    @Test
    fun `malformed responses produce a structured API error`() = runBlocking {
        val transport = QueueHttpClient(listOf(Ok("<html>not json</html>")))

        val error = WikipediaService(client(transport)).lookup(
            DictionaryRequest("Earth", LanguageCode.ENGLISH)
        ).getError()

        assertIs<ServiceError.InvalidResponseError>(error)
        assertTrue(error.message.contains("unexpected API response"))
    }

    @Test
    fun `both services share serialized throttling`() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val transport = QueueHttpClient(listOf(
            Ok("""{"pages":[]}"""),
            Ok("""{"pages":[]}""")
        ))
        val client = WikimediaClient(
            transport,
            minimumIntervalMillis = 300,
            clockMillis = { now },
            wait = { millis -> waits += millis; now += millis }
        )

        WikipediaService(client).lookup(DictionaryRequest("Earth", LanguageCode.ENGLISH))
        WiktionaryService(client).lookup(DictionaryRequest("hello", LanguageCode.ENGLISH))

        assertEquals(listOf(300L), waits)
    }

    @Test
    fun `plugin exposes distinct dictionary services`() = runBlocking {
        val plugin = WikimediaReferencePlugin()
        val context = FakePluginContext()

        plugin.initialize(context)
        plugin.onEnable()

        assertEquals(listOf("Wikipedia", "Wiktionary"), plugin.getServices().map { it.name })
        assertTrue(plugin.getServices().all { it is com.github.ahatem.qtranslate.api.dictionary.Dictionary })
        plugin.shutdown()
    }

    private fun client(httpClient: HttpClient) = WikimediaClient(httpClient, minimumIntervalMillis = 0)
    private fun failure(error: ServiceError) = IllegalStateException(error.message, error.cause)

    private fun pageJson(title: String, html: String): String =
        kotlinx.serialization.json.buildJsonObject {
            put("key", kotlinx.serialization.json.JsonPrimitive(title))
            put("title", kotlinx.serialization.json.JsonPrimitive(title))
            put("html", kotlinx.serialization.json.JsonPrimitive(html))
        }.toString()

    private class QueueHttpClient(responses: List<Result<String, ServiceError>>) : HttpClient {
        private val responses = ArrayDeque(responses)
        val urls = mutableListOf<String>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> {
            urls += url
            return responses.removeFirst()
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String?,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> = error("POST is not used by Wikimedia")
    }

    private companion object {
        val ENGLISH_WIKTIONARY_HTML = """
            <html><body><section><h2>English</h2>
              <section><h3>Interjection</h3><ol>
                <li>A greeting said when meeting someone.<ul><li>Hello, everyone.</li></ul></li>
                <li>A greeting used when answering the telephone.</li>
              </ol></section>
              <section><h3>Pronunciation</h3><ul><li>IPA: /həˈləʊ/</li></ul></section>
              <section><h3>Noun</h3><ol><li>An utterance of “hello”.</li></ol></section>
            </section></body></html>
        """.trimIndent()

        val ARABIC_WIKTIONARY_HTML = """
            <html><body><section><h2>عربية</h2>
              <section><h3>المعاني</h3><ol>
                <li>تَحِيَّة تقال للضيف.</li>
                <li>تَحِيَّة تقال عند لقاء شخص ما.</li>
              </ol></section>
              <section><h3>الترجمات</h3><ul><li>English: hello</li></ul></section>
            </section></body></html>
        """.trimIndent()
    }
}
