package com.github.ahatem.qtranslate.plugins.wikimedia

import com.github.ahatem.qtranslate.plugins.common.FakePluginContext

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.imagesearch.ImageSearchRequest
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
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
    fun `plugin exposes two dictionaries and an image search`() = runBlocking {
        val plugin = WikimediaReferencePlugin()
        val context = FakePluginContext()

        plugin.initialize(context)
        plugin.onEnable()

        assertEquals(
            listOf("Wikipedia", "Wiktionary", "Wikimedia Commons"),
            plugin.getServices().map { it.name }
        )
        assertEquals(
            listOf(
                setOf(ServiceRole.DICTIONARY),
                setOf(ServiceRole.DICTIONARY),
                setOf(ServiceRole.IMAGE_SEARCH)
            ),
            plugin.getServices().map { ServiceRole.of(it) }
        )
        plugin.shutdown()
    }

    // ── Commons image search ──────────────────────────────────────────────────

    @Test
    fun `image search reads urls, caption, credit and licence`() = runBlocking<Unit> {
        val transport = QueueHttpClient(listOf(Ok(COMMONS_JSON)))

        val results = CommonsImageSearchService(client(transport)).searchImages(
            ImageSearchRequest("borborygmi", LanguageCode.ENGLISH)
        ).getOrThrow(::failure).results

        val first = results.first()
        assertEquals("https://upload.wikimedia.org/thumb/gut.jpg", first.thumbnailUrl)
        assertEquals("https://upload.wikimedia.org/gut.jpg", first.fullUrl)
        assertEquals("https://commons.wikimedia.org/wiki/File:Gut_sounds.jpg", first.sourceUrl)
        // "File:" and the extension are noise once it is a caption under a picture.
        assertEquals("Gut sounds", first.title)
        // The credit arrives as a link, which would read as raw HTML on screen.
        assertEquals("Jane Roe", first.attribution)
        assertEquals("CC BY-SA 4.0", first.license)
    }

    @Test
    fun `a file Commons could not render a thumbnail for is dropped`() = runBlocking<Unit> {
        // Showing it would put a broken tile in the grid; there is nothing to display.
        val transport = QueueHttpClient(listOf(Ok(COMMONS_JSON)))

        val results = CommonsImageSearchService(client(transport)).searchImages(
            ImageSearchRequest("borborygmi", LanguageCode.ENGLISH)
        ).getOrThrow(::failure).results

        assertEquals(1, results.size)
    }

    @Test
    fun `finding nothing is an empty result, not an error`() = runBlocking<Unit> {
        // The popup says "no images found"; an error would put a red status bar in front of
        // someone for the ordinary case of an obscure word.
        val transport = QueueHttpClient(listOf(Ok("""{"batchcomplete":true}""")))

        val response = CommonsImageSearchService(client(transport)).searchImages(
            ImageSearchRequest("qwertyuiop", LanguageCode.ENGLISH)
        ).getOrThrow(::failure)

        assertTrue(response.results.isEmpty())
    }

    @Test
    fun `metadata holding a number instead of a string does not fail the response`() = runBlocking<Unit> {
        // extmetadata is typed per key: most entries are strings, some are numbers. A String
        // field would lose the whole response over a field we never read.
        val transport = QueueHttpClient(listOf(Ok(COMMONS_JSON_WITH_NUMERIC_METADATA)))

        val results = CommonsImageSearchService(client(transport)).searchImages(
            ImageSearchRequest("gut", LanguageCode.ENGLISH)
        ).getOrThrow(::failure).results

        assertEquals("CC0", results.single().license)
    }

    @Test
    fun `the media type option selects which Commons file types are searched`() = runBlocking<Unit> {
        suspend fun fileTypeFor(options: Map<String, String>): String {
            val transport = QueueHttpClient(listOf(Ok("""{"batchcomplete":true}""")))
            CommonsImageSearchService(client(transport)).searchImages(
                ImageSearchRequest("gut", LanguageCode.ENGLISH, options = options)
            )
            return transport.params.single()["gsrsearch"].toString()
        }

        assertTrue(fileTypeFor(mapOf("mediaType" to "PHOTOS")).endsWith("filetype:bitmap"))
        assertTrue(fileTypeFor(mapOf("mediaType" to "DIAGRAMS")).endsWith("filetype:drawing"))
        // Unset falls back to the declared default rather than sending nothing.
        assertTrue(fileTypeFor(emptyMap()).endsWith("filetype:bitmap|drawing"))
    }

    @Test
    fun `image search asks Commons for files rather than articles`() = runBlocking<Unit> {
        val transport = QueueHttpClient(listOf(Ok("""{"batchcomplete":true}""")))

        CommonsImageSearchService(client(transport)).searchImages(
            ImageSearchRequest("gut", LanguageCode.ENGLISH)
        )

        assertEquals("https://commons.wikimedia.org/w/api.php", transport.urls.single())
        // Namespace 6 is File:. Without it the search returns pages with no image on them.
        assertEquals(6, transport.params.single()["gsrnamespace"])
    }

    private fun client(httpClient: HttpClient) = WikimediaClient(httpClient, minimumIntervalMillis = 0)
    private fun failure(error: ServiceError) = IllegalStateException(error.message, error.cause)

    private fun pageJson(title: String, html: String): String =
        kotlinx.serialization.json.buildJsonObject {
            put("key", kotlinx.serialization.json.JsonPrimitive(title))
            put("title", kotlinx.serialization.json.JsonPrimitive(title))
            put("html", kotlinx.serialization.json.JsonPrimitive(html))
        }.toString()

    private class QueueHttpClient(responses: List<Result<String, ServiceError>>) : TextHttpClient() {
        private val responses = ArrayDeque(responses)
        val urls = mutableListOf<String>()
        val params = mutableListOf<Map<String, Any?>>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> {
            urls += url
            params += queryParams
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

        /** Two files: one complete, one Commons produced no thumbnail for. */
        val COMMONS_JSON = """
            {"query":{"pages":[
              {"title":"File:Gut sounds.jpg","imageinfo":[{
                "thumburl":"https://upload.wikimedia.org/thumb/gut.jpg",
                "url":"https://upload.wikimedia.org/gut.jpg",
                "descriptionurl":"https://commons.wikimedia.org/wiki/File:Gut_sounds.jpg",
                "extmetadata":{
                  "Artist":{"value":"<a href=\"/wiki/User:Jane\">Jane Roe</a>"},
                  "LicenseShortName":{"value":"CC BY-SA 4.0"}
                }}]},
              {"title":"File:Unrenderable.tif","imageinfo":[{
                "url":"https://upload.wikimedia.org/unrenderable.tif",
                "extmetadata":{}
              }]}
            ]}}
        """.trimIndent()

        val COMMONS_JSON_WITH_NUMERIC_METADATA = """
            {"query":{"pages":[
              {"title":"File:Gut.png","imageinfo":[{
                "thumburl":"https://upload.wikimedia.org/thumb/gut.png",
                "url":"https://upload.wikimedia.org/gut.png",
                "extmetadata":{
                  "LicenseShortName":{"value":"CC0"},
                  "AttributionRequired":{"value":0},
                  "Restrictions":{"value":""}
                }}]}
            ]}}
        """.trimIndent()

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
