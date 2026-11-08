package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.PluginJson
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class GoogleTranslatorServiceTest {

    private val request = TranslationRequest(
        text = "Hello",
        sourceLanguage = LanguageCode.AUTO,
        targetLanguage = LanguageCode.FRENCH
    )

    private fun createService(
        client: GoogleTestHttpClient,
        clock: AtomicLong,
        settings: GoogleSettings = GoogleSettings(),
        // Long unless a test is about the hedge, so a slow handler in an unrelated test cannot
        // start a fallback request behind that test's back.
        hedgeMillis: Long = HEDGE_NEVER_FIRES,
        health: GoogleEndpointHealth = GoogleEndpointHealth({ clock.get() }, { 0L })
    ) = GoogleTranslatorService(
        FakePluginContext(),
        settings,
        client,
        GoogleLanguageMapper,
        ApiConfig(),
        health,
        hedgeMillis,
        { clock.get() }
    )

    @Test
    fun `healthy primary is used once and never falls back`() = runBlocking {
        val client = GoogleTestHttpClient(primaryHandler = { Ok(PRIMARY_JSON) })
        val service = createService(client, AtomicLong(0))

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
    fun `official API omits source when auto detection is requested`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Ok(PRIMARY_JSON) },
            officialHandler = { Ok(OFFICIAL_JSON) }
        )
        val service = createService(client, AtomicLong(0), GoogleSettings(translateApiKey = "test-key"))

        service.translate(request).fold(
            success = {
                assertEquals("Bonjour", it.translatedText)
                assertEquals(LanguageCode.ENGLISH, it.detectedLanguage)
            },
            failure = { fail(it.message) }
        )

        assertEquals(1, client.officialCalls)
        assertEquals(0, client.primaryCalls)
        val body = PluginJson.parseToJsonElement(client.officialBodies.single()).jsonObject
        assertNull(body["source"])
        assertEquals("Hello", body["q"]?.jsonPrimitive?.content)
        assertEquals("fr", body["target"]?.jsonPrimitive?.content)
    }

    @Test
    fun `official API sends gn as the target for Guarani`() = runBlocking {
        val guarani = TranslationRequest(
            text = "Hello, how are you today?",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.GUARANI
        )
        val client = GoogleTestHttpClient(
            primaryHandler = { Ok(PRIMARY_JSON) },
            officialHandler = { Ok(OFFICIAL_JSON) }
        )
        val service = createService(client, AtomicLong(0), GoogleSettings(translateApiKey = "test-key"))

        service.translate(guarani).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )

        assertEquals(1, client.officialCalls)
        val body = PluginJson.parseToJsonElement(client.officialBodies.single()).jsonObject
        assertEquals("en", body["source"]?.jsonPrimitive?.content)
        assertEquals("gn", body["target"]?.jsonPrimitive?.content)
    }

    @Test
    fun `official API sends ka as the target for Georgian`() = runBlocking {
        val georgian = TranslationRequest(
            text = "Hello, how are you today?",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.GEORGIAN
        )
        val client = GoogleTestHttpClient(
            primaryHandler = { Ok(PRIMARY_JSON) },
            officialHandler = { Ok(OFFICIAL_JSON) }
        )
        val service = createService(client, AtomicLong(0), GoogleSettings(translateApiKey = "test-key"))

        service.translate(georgian).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )

        assertEquals(1, client.officialCalls)
        val body = PluginJson.parseToJsonElement(client.officialBodies.single()).jsonObject
        assertEquals("en", body["source"]?.jsonPrimitive?.content)
        assertEquals("ka", body["target"]?.jsonPrimitive?.content)
    }

    @Test
    fun `official API sends the mapped source for an explicit language`() = runBlocking {
        val explicit = TranslationRequest(
            text = "Bonjour",
            sourceLanguage = LanguageCode.FRENCH,
            targetLanguage = LanguageCode.ENGLISH
        )
        val client = GoogleTestHttpClient(
            primaryHandler = { Ok(PRIMARY_JSON) },
            officialHandler = { Ok(OFFICIAL_JSON) }
        )
        val service = createService(client, AtomicLong(0), GoogleSettings(translateApiKey = "test-key"))

        service.translate(explicit).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )

        assertEquals(1, client.officialCalls)
        val body = PluginJson.parseToJsonElement(client.officialBodies.single()).jsonObject
        assertEquals("fr", body["source"]?.jsonPrimitive?.content)
    }

    @Test
    fun `primary timeout falls back to the fallback endpoint`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        service.translate(request).fold(
            success = {
                assertEquals("Bonjour", it.translatedText)
                assertEquals(LanguageCode.ENGLISH, it.detectedLanguage)
            },
            failure = { fail(it.message) }
        )

        assertEquals(1, client.primaryCalls)
        assertEquals(1, client.fallbackCalls)
    }

    @Test
    fun `repeated transient failures open the circuit and bypass the primary`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        repeat(3) { attempt ->
            service.translate(request).fold(
                success = { assertEquals("Bonjour", it.translatedText) },
                failure = { fail("attempt $attempt failed: ${it.message}") }
            )
        }

        // Two failures open the circuit; the third call must not touch the primary at all.
        assertEquals(2, client.primaryCalls)
        assertEquals(3, client.fallbackCalls)
    }

    @Test
    fun `rate limit response opens the circuit immediately`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.RateLimitError("rate limited", retryAfterSeconds = 30)) },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        repeat(2) { service.translate(request) }

        assertEquals(1, client.primaryCalls)
        assertEquals(2, client.fallbackCalls)
    }

    @Test
    fun `the primary is attempted once when the transport would have retried a rate limit`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.RateLimitError("rate limited")) },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )

        // The primary is tried once from the first answer. A plain GET would have spent two retries
        // on the rate limit before the circuit ever saw the failure.
        assertEquals(1, client.primaryCalls)
        assertEquals(1, client.fallbackCalls)
    }

    @Test
    fun `a second translation while the circuit is open makes no primary attempt`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.RateLimitError("rate limited")) },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        repeat(2) { attempt ->
            service.translate(request).fold(
                success = { assertEquals("Bonjour", it.translatedText) },
                failure = { fail("attempt $attempt failed: ${it.message}") }
            )
        }

        // The single rate-limited attempt opens the circuit, so the second call never reaches the
        // primary and both translations come from the fallback.
        assertEquals(1, client.primaryCalls)
        assertEquals(2, client.fallbackCalls)
    }

    @Test
    fun `a rate limited primary does not delay the fallback`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.RateLimitError("rate limited", retryAfterSeconds = 5)) },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        val startedAt = System.nanoTime()
        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        // The request is not held for the Retry-After the transport would otherwise have honoured
        // before retrying; the fallback answers straight away.
        assertEquals(1, client.primaryCalls)
        assertTrue(elapsedMillis < 2_000, "translation took ${elapsedMillis}ms")
    }

    @Test
    fun `usable retry-after delays the next probe`() = runBlocking {
        val clock = AtomicLong(0)
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                if (index == 0) Err(ServiceError.RateLimitError("rate limited", retryAfterSeconds = 30))
                else Ok(PRIMARY_JSON)
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, clock)

        service.translate(request)
        assertEquals(1, client.primaryCalls)

        clock.set(29_999)
        service.translate(request)
        assertEquals(1, client.primaryCalls)

        clock.set(30_000)
        service.translate(request)
        assertEquals(2, client.primaryCalls)
    }

    @Test
    fun `transient service unavailable counts toward opening the circuit`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.ServiceUnavailableError("bad gateway")) },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        repeat(3) { service.translate(request) }

        assertEquals(2, client.primaryCalls)
    }

    @Test
    fun `authentication error is returned unchanged and does not open the circuit`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.AuthenticationError("invalid key")) }
        )
        val service = createService(client, AtomicLong(0))

        val first = service.translate(request)
        assertIs<ServiceError.AuthenticationError>(first.getError())
        assertEquals(0, client.fallbackCalls)
        assertEquals(1, client.primaryCalls)

        service.translate(request)
        assertEquals(2, client.primaryCalls)
    }

    @Test
    fun `invalid input error is returned unchanged and does not open the circuit`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.InvalidInputError("bad request")) }
        )
        val service = createService(client, AtomicLong(0))

        val first = service.translate(request)
        assertIs<ServiceError.InvalidInputError>(first.getError())
        assertEquals(0, client.fallbackCalls)
        assertEquals(1, client.primaryCalls)

        service.translate(request)
        assertEquals(2, client.primaryCalls)
    }

    @Test
    fun `unparseable primary body falls back to the fallback endpoint`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Ok("<html>nope</html>") },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )

        assertEquals(1, client.primaryCalls)
        assertEquals(1, client.fallbackCalls)
    }

    @Test
    fun `unparseable primary body does not open the circuit`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Ok("<html>nope</html>") },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        repeat(3) { attempt ->
            service.translate(request).fold(
                success = { assertEquals("Bonjour", it.translatedText) },
                failure = { fail("attempt $attempt failed: ${it.message}") }
            )
        }

        assertEquals(3, client.primaryCalls)
        assertEquals(3, client.fallbackCalls)
    }

    @Test
    fun `only one half-open probe is issued for concurrent callers`() = runBlocking {
        val clock = AtomicLong(0)
        val probeStarted = CompletableDeferred<Unit>()
        val probeRelease = CompletableDeferred<Unit>()
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                when (index) {
                    0, 1 -> Err(ServiceError.TimeoutError("timed out"))
                    2 -> {
                        probeStarted.complete(Unit)
                        probeRelease.await()
                        Ok(PRIMARY_JSON)
                    }
                    else -> Ok(PRIMARY_JSON)
                }
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, clock)

        service.translate(request)
        service.translate(request)
        assertEquals(2, client.primaryCalls)

        clock.set(2_000)
        val probe = async { service.translate(request) }
        probeStarted.await()

        val losers = List(4) { async { service.translate(request) } }
        losers.awaitAll()

        assertEquals(3, client.primaryCalls)
        assertEquals(6, client.fallbackCalls)

        probeRelease.complete(Unit)
        probe.await().fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
    }

    @Test
    fun `successful half-open probe returns the circuit to healthy`() = runBlocking {
        val clock = AtomicLong(0)
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                if (index < 2) Err(ServiceError.TimeoutError("timed out")) else Ok(PRIMARY_JSON)
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, clock)

        service.translate(request)
        service.translate(request)
        clock.set(2_000)

        service.translate(request)
        service.translate(request)

        assertEquals(4, client.primaryCalls)
        assertEquals(2, client.fallbackCalls)
    }

    @Test
    fun `failed half-open probe reopens the cooldown`() = runBlocking {
        val clock = AtomicLong(0)
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, clock)

        service.translate(request)
        service.translate(request)
        clock.set(2_000)
        service.translate(request)
        service.translate(request)
        assertEquals(3, client.primaryCalls)

        clock.set(6_000)
        service.translate(request)
        assertEquals(4, client.primaryCalls)
    }

    @Test
    fun `the flat client parses a nested array response`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Ok("""[["Bonjour","en"]]""") }
        )
        val service = createService(client, AtomicLong(0))

        service.translate(request).fold(
            success = {
                assertEquals("Bonjour", it.translatedText)
                assertEquals(LanguageCode.ENGLISH, it.detectedLanguage)
            },
            failure = { fail(it.message) }
        )
    }

    @Test
    fun `the flat client names the requested source when its reply omits one`() = runBlocking {
        // The live endpoint answers the text-only form whenever the request names a source, so the
        // reply carries no source field. The request is then the answer to "which language is this",
        // and a null here would strip the detected language from every explicit-source translation.
        val explicit = TranslationRequest(
            text = "Hello",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.FRENCH
        )
        val client = GoogleTestHttpClient(
            primaryHandler = { Ok(PRIMARY_TEXT_ONLY_JSON) }
        )
        val service = createService(client, AtomicLong(0))

        service.translate(explicit).fold(
            success = {
                assertEquals("Bonjour", it.translatedText)
                assertEquals(LanguageCode.ENGLISH, it.detectedLanguage)
            },
            failure = { fail(it.message) }
        )
    }

    @Test
    fun `the sentence client without a detected source still succeeds`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("""{"sentences":[{"trans":"Bonjour","orig":"Hello"}]}""") }
        )
        val service = createService(client, AtomicLong(0))

        service.translate(request).fold(
            success = {
                assertEquals("Bonjour", it.translatedText)
                assertNull(it.detectedLanguage)
            },
            failure = { fail(it.message) }
        )
    }

    @Test
    fun `empty primary response fails with an invalid response error`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Ok("[]") },
            // The unusable first answer is retryable, so the second path is asked; making that fail
            // too is what surfaces the primary's own classification.
            fallbackHandler = { Err(ServiceError.TimeoutError("timed out")) }
        )
        val service = createService(client, AtomicLong(0))

        // The caller is left with the second path's failure, so that is the type; what must survive
        // is the first path's own classification, which is what tells a throttled host apart from
        // one that answered with something unusable.
        val error = service.translate(request).getError()
        assertIs<ServiceError.TimeoutError>(error)
        assertContains(error.message, "primary endpoint returned an unusable response")
    }

    @Test
    fun `malformed second path response fails with an invalid response error`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("<html>nope</html>") }
        )
        val service = createService(client, AtomicLong(0))

        // This path answered, it just answered with something unusable, so its own type is what the
        // caller sees and the first path's timeout is stated beside it.
        val error = service.translate(request).getError()
        assertIs<ServiceError.InvalidResponseError>(error)
        assertContains(error.message, "primary endpoint timed out")
        assertContains(error.message, "sentence client returned an unusable response")
    }

    @Test
    fun `an unsupported primary object shape fails with an invalid response error`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Ok("""{"translated":"Bonjour"}""") },
            fallbackHandler = { Err(ServiceError.TimeoutError("timed out")) }
        )
        val service = createService(client, AtomicLong(0))

        val error = service.translate(request).getError()
        assertIs<ServiceError.TimeoutError>(error)
        assertContains(error.message, "primary endpoint returned an unusable response")
    }

    @Test
    fun `the translator never contacts the host most hosts files block`() = runBlocking {
        // The reporter's environment sinkholes clients5.google.com, which used to be the fallback.
        // Every path is exercised here and none of them may name that host, so a future change that
        // reintroduces it fails this test instead of failing for the reporter.
        val scenarios: List<Pair<String, GoogleTestHttpClient>> = listOf(
            "healthy" to GoogleTestHttpClient(primaryHandler = { Ok(PRIMARY_JSON) }),
            "primary throttled" to GoogleTestHttpClient(
                primaryHandler = { Err(ServiceError.RateLimitError("rate limited", retryAfterSeconds = 30)) },
                fallbackHandler = { Ok(FALLBACK_FLAT) }
            ),
            "primary stalled" to GoogleTestHttpClient(
                primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
                fallbackHandler = { Ok(FALLBACK_FLAT) }
            ),
            "primary unusable" to GoogleTestHttpClient(
                primaryHandler = { Ok("<html>nope</html>") },
                fallbackHandler = { Ok(FALLBACK_FLAT) }
            ),
            "both unusable" to GoogleTestHttpClient(
                primaryHandler = { Ok("<html>nope</html>") },
                fallbackHandler = { Ok("<html>nope</html>") }
            )
        )
        for ((name, client) in scenarios) {
            val service = createService(client, AtomicLong(0))
            service.translate(request)
            val contacted = client.urls.filter { it.contains("clients5") }
            assertTrue(contacted.isEmpty(), "$name contacted a blocked host: $contacted")
            assertTrue(
                client.urls.all { it.contains("translate.googleapis.com") },
                "$name contacted something other than the Google host: ${client.urls}"
            )
        }
    }

    @Test
    fun `the primary is the flat client and the second path asks for no dictionary payload`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )

        val first = client.queryParams.first()
        assertEquals("dict-chrome-ex", first["client"])
        assertTrue(first.containsKey("q"), "the first path must send the text")
        assertTrue(!first.containsKey("dt"), "the flat client does not take a feature list")

        val second = client.queryParams[1]
        assertEquals("gtx", second["client"])
        // Only the translation itself: the dictionary payload it used to request cost ~750 ms per
        // request and nothing in the application ever displayed the alternatives it produced.
        assertEquals("t", second["dt"])
    }

    @Test
    fun `a rate limited primary is asked once and the second path answers`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.RateLimitError("429 Too Many Requests", retryAfterSeconds = 20)) },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        // getOnce on the first path: a throttle costs one wire attempt, not three.
        assertEquals(1, client.primaryCalls)
        assertEquals(1, client.fallbackCalls)
    }

    @Test
    fun `the flat client keeps line breaks, punctuation and non-BMP characters`() = runBlocking {
        val translated = "Première ligne.\nDeuxième ligne : « ok » 🎉 𝔘 👍🏽"
        val client = GoogleTestHttpClient(
            primaryHandler = { Ok("[[\"$translated\",\"en\"]]") }
        )
        val service = createService(client, AtomicLong(0))

        service.translate(request).fold(
            success = {
                assertEquals(translated, it.translatedText)
                assertEquals(LanguageCode.ENGLISH, it.detectedLanguage)
            },
            failure = { fail(it.message) }
        )
    }

    @Test
    fun `the flat client reports the source it detected for an auto request`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Ok("""[["Bonjour","fr"]]""") }
        )
        val service = createService(client, AtomicLong(0))

        service.translate(request).fold(
            success = { assertEquals(LanguageCode.FRENCH, it.detectedLanguage) },
            failure = { fail(it.message) }
        )
    }

    @Test
    fun `backward translation goes through the second path`() = runBlocking {
        val backward = TranslationRequest(
            text = "Bonjour",
            sourceLanguage = LanguageCode.FRENCH,
            targetLanguage = LanguageCode.ENGLISH
        )
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("""{"sentences":[{"trans":"Hello","orig":"Bonjour"}],"src":"fr"}""") }
        )
        val service = createService(client, AtomicLong(0))

        service.translate(backward).fold(
            success = {
                assertEquals("Hello", it.translatedText)
                assertEquals(LanguageCode.FRENCH, it.detectedLanguage)
            },
            failure = { fail(it.message) }
        )
        assertEquals(1, client.fallbackCalls)
    }

    @Test
    fun `cancellation during the primary wait does not fall back`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                if (index == 0) {
                    started.complete(Unit)
                    gate.await()
                    Ok(PRIMARY_JSON)
                } else {
                    Ok(PRIMARY_JSON)
                }
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        val job = launch { service.translate(request) }
        started.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(0, client.fallbackCalls)

        // Health was left untouched, so the next call still uses the primary.
        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(2, client.primaryCalls)
    }

    @Test
    fun `late failure of an older request does not reopen a healed circuit`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                if (index == 0) {
                    started.complete(Unit)
                    gate.await()
                    Err(ServiceError.TimeoutError("timed out"))
                } else {
                    Ok(PRIMARY_JSON)
                }
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        val older = async { service.translate(request) }
        started.await()

        val newer = async { service.translate(request) }
        newer.await().fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )

        gate.complete(Unit)
        older.await().fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )

        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(3, client.primaryCalls)
        assertEquals(1, client.fallbackCalls)
    }

    @Test
    fun `rate limit without retry-after opens the circuit immediately`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.RateLimitError("rate limited")) },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        service.translate(request)
        service.translate(request)

        // The first rate limit opens the circuit, so the second call never reaches the primary.
        assertEquals(1, client.primaryCalls)
        assertEquals(2, client.fallbackCalls)
    }

    @Test
    fun `a stale success cannot close a circuit opened after its request began`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                when (index) {
                    0 -> {
                        started.complete(Unit)
                        gate.await()
                        Ok(PRIMARY_JSON)
                    }
                    1, 2 -> Err(ServiceError.TimeoutError("timed out"))
                    else -> Ok(PRIMARY_JSON)
                }
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0))

        val older = async { service.translate(request) }
        started.await()

        // Two transient failures open the circuit while the older request is still in flight.
        service.translate(request)
        service.translate(request)
        assertEquals(3, client.primaryCalls)

        gate.complete(Unit)
        older.await().fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )

        // The older success belongs to a previous generation and must not heal the circuit.
        service.translate(request)
        assertEquals(3, client.primaryCalls)
        assertEquals(3, client.fallbackCalls)
    }

    @Test
    fun `stale failures cannot reopen a circuit healed by a successful probe`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val clock = AtomicLong(0)
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                when (index) {
                    0 -> {
                        firstStarted.complete(Unit)
                        gate.await()
                        Err(ServiceError.TimeoutError("timed out"))
                    }
                    1 -> {
                        secondStarted.complete(Unit)
                        gate.await()
                        Err(ServiceError.TimeoutError("timed out"))
                    }
                    2, 3 -> Err(ServiceError.TimeoutError("timed out"))
                    else -> Ok(PRIMARY_JSON)
                }
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, clock)

        val first = async { service.translate(request) }
        firstStarted.await()
        val second = async { service.translate(request) }
        secondStarted.await()

        // Two further failures open the circuit while the older requests are still in flight.
        service.translate(request)
        service.translate(request)
        assertEquals(4, client.primaryCalls)

        clock.set(2_000)
        service.translate(request)
        assertEquals(5, client.primaryCalls)

        // The probe heals the circuit; the older requests now resolve with stale failures.
        gate.complete(Unit)
        first.await()
        second.await()

        service.translate(request)
        assertEquals(6, client.primaryCalls)
    }

    @Test
    fun `cancelling a half-open probe does not strand the circuit`() = runBlocking {
        val clock = AtomicLong(0)
        val probeStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                when (index) {
                    0, 1 -> Err(ServiceError.TimeoutError("timed out"))
                    2 -> {
                        probeStarted.complete(Unit)
                        gate.await()
                        Ok(PRIMARY_JSON)
                    }
                    else -> Ok(PRIMARY_JSON)
                }
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, clock)

        service.translate(request)
        service.translate(request)
        assertEquals(2, client.primaryCalls)

        clock.set(2_000)
        val probe = async { service.translate(request) }
        probeStarted.await()
        probe.cancelAndJoin()

        // The abandoned probe reopens the cooldown instead of leaving the circuit half open.
        service.translate(request)
        assertEquals(3, client.primaryCalls)

        clock.set(4_000)
        service.translate(request)
        assertEquals(4, client.primaryCalls)
        assertEquals(3, client.fallbackCalls)
    }

    @Test
    fun `a cancelled probe is not recorded as success or failure`() = runBlocking {
        val clock = AtomicLong(0)
        val probeStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                when (index) {
                    0, 1 -> Err(ServiceError.TimeoutError("timed out"))
                    2 -> {
                        probeStarted.complete(Unit)
                        gate.await()
                        Ok(PRIMARY_JSON)
                    }
                    else -> Ok(PRIMARY_JSON)
                }
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, clock)

        service.translate(request)
        service.translate(request)
        clock.set(2_000)

        val probe = async { service.translate(request) }
        probeStarted.await()
        probe.cancelAndJoin()

        // Before the reopened cooldown elapses a call must not reach the primary, so the cancelled
        // probe neither healed the circuit nor reset it.
        clock.set(2_999)
        service.translate(request)
        assertEquals(3, client.primaryCalls)
        assertEquals(3, client.fallbackCalls)
    }

    @Test
    fun `only one half-open probe is live at a time`() = runBlocking {
        val clock = AtomicLong(0)
        val probeStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                when (index) {
                    0, 1 -> Err(ServiceError.TimeoutError("timed out"))
                    2 -> {
                        probeStarted.complete(Unit)
                        gate.await()
                        Ok(PRIMARY_JSON)
                    }
                    else -> Ok(PRIMARY_JSON)
                }
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, clock)

        service.translate(request)
        service.translate(request)
        clock.set(2_000)

        val probe = async { service.translate(request) }
        probeStarted.await()

        val others = List(5) { async { service.translate(request) } }
        others.awaitAll()

        // The probe lease is outstanding, so no other caller may reach the primary.
        assertEquals(3, client.primaryCalls)

        gate.complete(Unit)
        probe.await().fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(3, client.primaryCalls)
    }

    // --- Fallback reachability failures (#365) -----------------------------------------
    //
    // When the primary throttles, requests go to the fallback host. That host can itself be
    // unreachable — a hosts-file sinkhole refuses the connection, DNS can fail, the socket can
    // reset, or the endpoint can answer with a provider-level HTTP error. These tests keep the
    // connection-level failures (refused, DNS, reset) distinct from the HTTP/provider failures
    // and pin down what each means for the circuit, for recovery, and for the error the user is
    // shown.
    //
    // The asserted call counts hold under the fake transport's retry policy, which retries only
    // a rate limit. Production keeps Ktor's default exception retry — `retryIf` overrides only
    // the status condition — so production retries a refused, DNS-dead, reset, or 5xx fallback
    // attempt up to two extra times with exponential backoff before the service sees the
    // failure. The circuit conclusions below do not depend on the attempt count: fallback
    // failures never feed GoogleEndpointHealth, the cooldown still runs out and admits a probe,
    // and nothing pins to the unreachable host.

    private val refusedFallback: suspend (Int) -> Result<String, ServiceError> =
        { Err(ServiceError.NetworkError("Network error: Connection refused: getsockopt")) }

    @Test
    fun `a refused second path is reported with the primary failure and its classification`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.RateLimitError("rate limited")) },
            fallbackHandler = refusedFallback
        )
        val service = createService(client, AtomicLong(0))

        val error = service.translate(request).getError()

        // The failure is still the fallback's connection-level failure, so it keeps that type and
        // is not rewritten into a cause it does not have. What was missing was context: a bare
        // "Connection refused" left the reporter unable to tell a throttled primary from a host
        // their machine cannot reach. Both stages are now stated, each classified.
        assertIs<ServiceError.NetworkError>(error)
        assertContains(error.message, "primary endpoint rate limited")
        assertContains(error.message, "sentence client unreachable")
        assertContains(error.message, "Network error: Connection refused: getsockopt")
        // The status bar shows the first line only, so the classification has to fit there.
        val summary = error.message.lineSequence().first()
        assertTrue(summary.length <= 120, "status bar line is ${summary.length} chars: $summary")
        assertEquals(1, client.fallbackCalls)
    }

    @Test
    fun `a dead fallback does not pin requests away from a recovered primary`() = runBlocking {
        val clock = AtomicLong(0)
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                if (index == 0) Err(ServiceError.RateLimitError("rate limited")) else Ok(PRIMARY_JSON)
            },
            fallbackHandler = refusedFallback
        )
        val service = createService(client, clock)

        // The throttle opens the circuit and the refused fallback fails the request.
        assertIs<ServiceError.NetworkError>(service.translate(request).getError())
        assertEquals(1, client.primaryCalls)
        assertEquals(1, client.fallbackCalls)

        // While the cooldown runs, requests go straight to the dead fallback. That detour is
        // bounded by the cooldown, not a permanent pin to the unreachable host.
        assertIs<ServiceError.NetworkError>(service.translate(request).getError())
        assertEquals(1, client.primaryCalls)
        assertEquals(2, client.fallbackCalls)

        // After the cooldown a single probe returns to the primary, which has recovered: the
        // request succeeds through the primary even though the fallback is still refused.
        clock.set(2_000)
        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(2, client.primaryCalls)

        // The probe healed the circuit, so the primary serves directly again and the dead
        // fallback is left alone.
        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(3, client.primaryCalls)
        assertEquals(2, client.fallbackCalls)
    }

    @Test
    fun `DNS and reset fallback failures each surface their own connection error`() = runBlocking {
        val clock = AtomicLong(0)
        val fallbackErrors = listOf(
            "Network error: UnknownHostException: clients5.google.com",
            "Network error: Connection reset"
        )
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.RateLimitError("rate limited")) },
            fallbackHandler = { index -> Err(ServiceError.NetworkError(fallbackErrors[index])) }
        )
        val service = createService(client, clock)

        val dnsError = service.translate(request).getError()
        assertIs<ServiceError.NetworkError>(dnsError)
        assertContains(dnsError.message, "primary endpoint rate limited")
        assertContains(dnsError.message, fallbackErrors[0])

        // A connection-level fallback failure never feeds the circuit: the cooldown opened by
        // the primary's rate limit still runs out, admits a probe, and the probe's failure is
        // the primary's own rate limit reopening it. The next request still reaches the
        // fallback, whose reset failure surfaces as itself.
        clock.set(2_000)
        val resetError = service.translate(request).getError()
        assertIs<ServiceError.NetworkError>(resetError)
        assertContains(resetError.message, fallbackErrors[1])
        assertEquals(2, client.primaryCalls)
        assertEquals(2, client.fallbackCalls)
    }

    @Test
    fun `a fallback provider HTTP failure is distinct from a fallback connection failure`() = runBlocking {
        val clock = AtomicLong(0)
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                if (index == 0) Err(ServiceError.RateLimitError("rate limited")) else Ok(PRIMARY_JSON)
            },
            fallbackHandler = {
                Err(ServiceError.ServiceUnavailableError("HTTP 500 for https://clients5.google.com/translate_a/t"))
            }
        )
        val service = createService(client, clock)

        // The fallback was reached and answered, so the failure is provider-level and surfaces
        // as one, not as a connection error.
        assertIs<ServiceError.ServiceUnavailableError>(service.translate(request).getError())

        // The fallback's HTTP failure does not feed the circuit: after the primary's cooldown
        // the probe heals it and translation recovers without another fallback attempt.
        clock.set(2_000)
        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(2, client.primaryCalls)
        assertEquals(1, client.fallbackCalls)
    }

    @Test
    fun `fallback connection failures do not interfere with the primary failure count`() = runBlocking {
        val clock = AtomicLong(0)
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = refusedFallback
        )
        val service = createService(client, clock)

        // Both endpoints fail end to end: the primary times out, the fallback is refused, and
        // the request surfaces the fallback's connection error.
        assertIs<ServiceError.NetworkError>(service.translate(request).getError())
        assertIs<ServiceError.NetworkError>(service.translate(request).getError())
        assertEquals(2, client.primaryCalls)
        assertEquals(2, client.fallbackCalls)

        // The circuit opened on the primary's second consecutive timeout, exactly as it would
        // have with a working fallback: the fallback's failures neither accelerated nor delayed
        // it. The third request skips the primary for the cooldown and meets the dead fallback.
        assertIs<ServiceError.NetworkError>(service.translate(request).getError())
        assertEquals(2, client.primaryCalls)
        assertEquals(3, client.fallbackCalls)
    }

    // --- A throttled primary must not hold back a working fallback (#244) ---------------
    //
    // The reporter's log is sequential: the primary was given its whole request timeout and only
    // then was the working fallback asked, so every translation paid the primary's budget (4.9 s
    // in the log) before the fallback's 0.3 s answer could even start. The hedge below bounds what
    // a user waits without shortening the primary's own budget and without discarding a slow
    // primary that does answer, so it cannot break a fallback that is unreachable (#365).
    //
    // A missed hedge is the translator's own business, not a primary failure: `GoogleEndpointHealth`
    // is shared with the fallback-less spell checker, so a merely slow primary must never open that
    // circuit. The translator keeps a private note instead and re-probes the primary later.

    @Test
    fun `a slow primary no longer holds back a working fallback`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                // Throttled into a slow body rather than a 429: still working, not answering yet.
                delay(3_000)
                Ok(PRIMARY_JSON)
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0), hedgeMillis = 150)

        val startedAt = System.nanoTime()
        val result = service.translate(request)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        result.fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(1, client.fallbackCalls)
        assertTrue(elapsedMillis >= 150, "returned before the 150ms hedge: ${elapsedMillis}ms")
        // The fallback answered just after the hedge; the rest of the primary's 3 s was not
        // waited for. Before this change the call took the primary's whole 3000 ms.
        assertTrue(elapsedMillis < 1_500, "waited for the throttled primary: ${elapsedMillis}ms")
    }

    @Test
    fun `a slow primary still serves the request when the fallback cannot be reached`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                delay(400)
                Ok(PRIMARY_JSON)
            },
            fallbackHandler = refusedFallback
        )
        val service = createService(client, AtomicLong(0), hedgeMillis = 100)

        val startedAt = System.nanoTime()
        val result = service.translate(request)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        // The fallback was asked because the primary missed the budget, it refused the
        // connection, and the primary's own answer is still the one returned: an unreachable
        // fallback (#365) cannot turn a slow but working primary into a failure.
        result.fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(1, client.primaryCalls)
        assertEquals(1, client.fallbackCalls)
        assertTrue(elapsedMillis >= 400, "returned before the primary answered: ${elapsedMillis}ms")
        assertTrue(elapsedMillis < 1_500, "translation took ${elapsedMillis}ms")
    }

    @Test
    fun `a primary that keeps missing the hedge budget is answered by the fallback directly`() = runBlocking {
        val clock = AtomicLong(0)
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                delay(2_000)
                Ok(PRIMARY_JSON)
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, clock, hedgeMillis = 100)

        repeat(3) { attempt ->
            service.translate(request).fold(
                success = { assertEquals("Bonjour", it.translatedText) },
                failure = { fail("attempt $attempt failed: ${it.message}") }
            )
        }

        // A missed budget is remembered by the translator itself, so the second request makes it
        // prefer the fallback and the third is served without paying the hedge again: the hedge is a
        // bounded detour, not a second provider request on every translation. The primary is not
        // failing here, so nothing about it reaches the shared circuit.
        assertEquals(2, client.primaryCalls)
        assertEquals(3, client.fallbackCalls)
    }

    @Test
    fun `a slow but working primary still serves spell check after two hedged translations`() = runBlocking {
        val clock = AtomicLong(0)
        val health = GoogleEndpointHealth({ clock.get() }, { 0L })
        val client = GoogleTestHttpClient(
            primaryHandler = {
                // Consistently ~2.5 s: longer than the hedge, well inside any request timeout, and
                // an answer every time. This is the population the hedge is for, and it used to get
                // working translations *and* working spell checking.
                delay(2_500)
                Ok(PRIMARY_JSON)
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) },
            spellHandler = { Ok(SPELL_JSON) }
        )
        val translator = createService(client, clock, hedgeMillis = 100, health = health)

        repeat(2) { attempt ->
            translator.translate(request).fold(
                success = { assertEquals("Bonjour", it.translatedText) },
                failure = { fail("translation $attempt failed: ${it.message}") }
            )
        }
        assertEquals(2, client.primaryCalls)
        assertEquals(2, client.fallbackCalls)

        // Both missed budgets belonged to the translator's own patience. `GoogleEndpointHealth` is
        // shared with the spell checker, which has no second host: a permit it cannot get is a
        // failed spell check, so the hedge must not have opened the circuit here.
        val spellChecker = GoogleSpellCheckerService(
            FakePluginContext(),
            client,
            GoogleLanguageMapper,
            ApiConfig(),
            health
        )
        val spellResult = spellChecker.check(SpellCheckRequest("Helo world."))
        assertTrue(spellResult.isOk, "spell check failed: ${spellResult.getError()?.message}")
        assertEquals(2, client.primaryCalls)
        assertEquals(1, client.spellCalls)
    }

    @Test
    fun `the translator returns to the primary once it answers inside the budget again`() = runBlocking {
        val clock = AtomicLong(0)
        val health = GoogleEndpointHealth({ clock.get() }, { 0L })
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                // Slow for the first two hedged requests, then fast again: the primary recovers.
                if (index < 2) delay(5_000)
                Ok(PRIMARY_JSON)
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) },
            spellHandler = { Ok(SPELL_JSON) }
        )
        val translator = createService(client, clock, hedgeMillis = 100, health = health)

        repeat(2) { translator.translate(request) }
        assertEquals(2, client.primaryCalls)
        assertEquals(2, client.fallbackCalls)

        // While the quiet period runs the fallback is asked on its own: no hedge, no primary call.
        translator.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(2, client.primaryCalls)
        assertEquals(3, client.fallbackCalls)

        // Once it has elapsed the primary is asked again, hedged as usual. It answers inside the
        // budget this time, so it serves the request and the note about it being slow is dropped.
        clock.set(60_000)
        translator.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(3, client.primaryCalls)
        assertEquals(3, client.fallbackCalls)

        // Direct primary use is back: the next translation costs one request again.
        translator.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(4, client.primaryCalls)
        assertEquals(3, client.fallbackCalls)

        // And the shared circuit was never touched, so spell checking kept working throughout.
        val spellChecker = GoogleSpellCheckerService(
            FakePluginContext(),
            client,
            GoogleLanguageMapper,
            ApiConfig(),
            health
        )
        val spellResult = spellChecker.check(SpellCheckRequest("Helo world."))
        assertTrue(spellResult.isOk, "spell check failed: ${spellResult.getError()?.message}")
    }

    @Test
    fun `a fallback that dies while it is preferred still lets the slow primary serve`() = runBlocking {
        val clock = AtomicLong(0)
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                if (index < 2) delay(5_000)
                Ok(PRIMARY_JSON)
            },
            fallbackHandler = { index ->
                // The fallback answers twice, which is what makes the translator prefer it, and then
                // becomes unreachable.
                if (index < 2) Ok(FALLBACK_FLAT) else refusedFallback(index)
            }
        )
        val service = createService(client, clock, hedgeMillis = 100)

        repeat(2) { service.translate(request) }
        assertEquals(2, client.primaryCalls)

        // Preferring the fallback must not make an unreachable fallback fatal: the slow primary is
        // asked and its answer is returned (#365).
        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(3, client.primaryCalls)
        assertEquals(3, client.fallbackCalls)

        // Its failure also drops the preference, so the next request pays the hedge again rather
        // than asking the dead host first.
        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        assertEquals(4, client.primaryCalls)
        assertEquals(3, client.fallbackCalls)
    }

    @Test
    fun `a half-open probe is not raced by the hedge so a slow recovery heals the circuit`() = runBlocking {
        val clock = AtomicLong(0)
        val health = GoogleEndpointHealth({ clock.get() }, { 0L })
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                when {
                    // Two genuine failures open the circuit, as they always did.
                    index < 2 -> Err(ServiceError.TimeoutError("timed out"))
                    // The probe is slow but working: it is the answer the circuit is waiting for.
                    index == 2 -> {
                        delay(2_500)
                        Ok(PRIMARY_JSON)
                    }
                    else -> Ok(PRIMARY_JSON)
                }
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) },
            spellHandler = { Ok(SPELL_JSON) }
        )
        val translator = createService(client, clock, hedgeMillis = 100, health = health)

        translator.translate(request)
        translator.translate(request)
        assertEquals(2, client.primaryCalls)
        assertEquals(2, client.fallbackCalls)

        clock.set(2_000)
        translator.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )

        // The probe was awaited instead of being cancelled at the hedge, so its own answer healed
        // the circuit and the fallback was never needed. Cancelling it would have reopened the
        // cooldown and left the fallback-less spell checker unavailable while the primary works.
        assertEquals(3, client.primaryCalls)
        assertEquals(2, client.fallbackCalls)

        val spellChecker = GoogleSpellCheckerService(
            FakePluginContext(),
            client,
            GoogleLanguageMapper,
            ApiConfig(),
            health
        )
        val spellResult = spellChecker.check(SpellCheckRequest("Helo world."))
        assertTrue(spellResult.isOk, "spell check failed: ${spellResult.getError()?.message}")
    }

    @Test
    fun `a fast primary failure reaches the fallback without waiting for the hedge`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.RateLimitError("rate limited")) },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0), hedgeMillis = 5_000)

        val startedAt = System.nanoTime()
        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        // A 429 is an answer, so the fallback is asked at once rather than at the hedge deadline.
        assertEquals(1, client.fallbackCalls)
        assertTrue(elapsedMillis < 1_000, "translation took ${elapsedMillis}ms")
    }

    @Test
    fun `an ordinary primary answer never costs a second provider request`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                delay(100)
                Ok(PRIMARY_JSON)
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = createService(client, AtomicLong(0), hedgeMillis = 5_000)

        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )

        // The hedge is a budget, not a race: one translation never costs two provider requests,
        // and a primary that answers in time is never replaced by the fallback.
        assertEquals(1, client.primaryCalls)
        assertEquals(0, client.fallbackCalls)
    }

    @Test
    fun `the shipped hedge budget bounds the wait for a slow primary`() = runBlocking {
        // No hedge override here: this is the delay a user actually gets, with the shipped default.
        val client = GoogleTestHttpClient(
            primaryHandler = { index ->
                delay(10_000)
                Ok(PRIMARY_JSON)
            },
            fallbackHandler = { Ok(FALLBACK_FLAT) }
        )
        val service = GoogleTranslatorService(
            FakePluginContext(),
            GoogleSettings(),
            client,
            GoogleLanguageMapper,
            ApiConfig(),
            GoogleEndpointHealth({ 0L }, { 0L })
        )

        val startedAt = System.nanoTime()
        service.translate(request).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail(it.message) }
        )
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(elapsedMillis >= 1_500, "returned before the 2s hedge: ${elapsedMillis}ms")
        assertTrue(elapsedMillis < 6_000, "waited for the throttled primary: ${elapsedMillis}ms")
        assertEquals(1, client.fallbackCalls)
    }

    // --- The error a user is left with when both endpoints fail (#365) -------------------
    //
    // A bare "Connection refused" names neither of the two things that went wrong. These tests pin
    // the message contract: the fallback's own error type survives, both stages are classified in
    // the line the status bar shows, and the fallback's own detail is still there in full.

    @Test
    fun `a timeout on both endpoints stays a timeout and names both stages`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = {
                Err(ServiceError.TimeoutError("Request timed out: https://translate.googleapis.com/translate_a/single"))
            },
            fallbackHandler = {
                Err(ServiceError.TimeoutError("Request timed out: https://clients5.google.com/translate_a/t"))
            }
        )
        val service = createService(client, AtomicLong(0))

        val error = service.translate(request).getError()

        assertIs<ServiceError.TimeoutError>(error)
        assertContains(error.message, "primary endpoint timed out")
        assertContains(error.message, "sentence client timed out")
    }

    @Test
    fun `a fallback failure after a skipped primary says the primary was skipped`() = runBlocking {
        val clock = AtomicLong(0)
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.RateLimitError("rate limited", retryAfterSeconds = 30)) },
            fallbackHandler = refusedFallback
        )
        val service = createService(client, clock)

        // The rate limit opens the circuit, so the second request never reaches the primary. The
        // user is still told which endpoint was not tried, and why.
        service.translate(request)
        val error = service.translate(request).getError()

        assertIs<ServiceError.NetworkError>(error)
        assertContains(error.message, "primary endpoint skipped after recent failures")
        assertContains(error.message, "Network error: Connection refused: getsockopt")
    }

    @Test
    fun `an unusable fallback answer keeps its own error type in the combined message`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.RateLimitError("rate limited")) },
            fallbackHandler = { Ok("[]") }
        )
        val service = createService(client, AtomicLong(0))

        val error = service.translate(request).getError()

        assertIs<ServiceError.InvalidResponseError>(error)
        assertContains(error.message, "primary endpoint rate limited")
        assertContains(error.message, "sentence client returned an unusable response")
        // The fallback answered, it just answered badly, so a reachability hint would be wrong.
        assertTrue(!error.message.contains("hosts-file"), error.message)
    }

    private companion object {
        /**
         * What the translator's first path (the flat client) actually answers for an auto-detected
         * source: a one-element array around a field array carrying the translation and the source
         * it found. An explicit source answers the text-only form below instead.
         */
        const val PRIMARY_JSON = """[["Bonjour","en"]]"""

        /** The text-only form the flat client answers when the request names the source. */
        const val PRIMARY_TEXT_ONLY_JSON = """["Bonjour"]"""

        /** What the translator's second path (the sentence client) actually answers. */
        const val FALLBACK_FLAT = """{"sentences":[{"trans":"Bonjour","orig":"Hello"}],"src":"en"}"""
        const val OFFICIAL_JSON = """{"data":{"translations":[{"translatedText":"Bonjour","detectedSourceLanguage":"en"}]}}"""

        /** Longer than any test's own latency, so the hedge only fires where a test asks it to. */
        /** A payload the spell checker recognises, for tests that translate and spell check. */
        const val SPELL_JSON = """{"sentences":[{"trans":"translated","orig":"original"}],"src":"en"}"""

        const val HEDGE_NEVER_FIRES = 60_000L
    }
}
