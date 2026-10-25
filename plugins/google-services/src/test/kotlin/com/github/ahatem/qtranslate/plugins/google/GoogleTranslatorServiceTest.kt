package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
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
        settings: GoogleSettings = GoogleSettings()
    ) = GoogleTranslatorService(
        FakePluginContext(),
        settings,
        client,
        GoogleLanguageMapper,
        ApiConfig(),
        GoogleEndpointHealth({ clock.get() }, { 0L })
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
    fun `fallback parses a nested array response`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("""[["Bonjour","en"]]""") }
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
    fun `fallback parses a flat array response`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("""["Bonjour","en"]""") }
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
    fun `fallback response without detected language still succeeds`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("""["Bonjour"]""") }
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
    fun `empty fallback response fails with an invalid response error`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("[]") }
        )
        val service = createService(client, AtomicLong(0))

        assertIs<ServiceError.InvalidResponseError>(service.translate(request).getError())
        Unit
    }

    @Test
    fun `malformed fallback response fails with an invalid response error`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("<html>nope</html>") }
        )
        val service = createService(client, AtomicLong(0))

        assertIs<ServiceError.InvalidResponseError>(service.translate(request).getError())
        Unit
    }

    @Test
    fun `unsupported fallback object shape fails with an invalid response error`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("""{"translated":"Bonjour"}""") }
        )
        val service = createService(client, AtomicLong(0))

        assertIs<ServiceError.InvalidResponseError>(service.translate(request).getError())
        Unit
    }

    @Test
    fun `backward translation goes through the fallback endpoint`() = runBlocking {
        val backward = TranslationRequest(
            text = "Bonjour",
            sourceLanguage = LanguageCode.FRENCH,
            targetLanguage = LanguageCode.ENGLISH
        )
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.TimeoutError("timed out")) },
            fallbackHandler = { Ok("""["Hello","fr"]""") }
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
    // and pin down what each means for the circuit and for recovery.
    //
    // A refused connection is a transport exception, which the transport's retry policy never
    // retries (it retries HTTP statuses only), so each fallback failure below is one wire attempt.
    // The HTTP-provider case is not held to an attempt count: production retries a 5xx GET while
    // the fake transport models only the 429 policy, so the counts would not agree.

    private val refusedFallback: suspend (Int) -> Result<String, ServiceError> =
        { Err(ServiceError.NetworkError("Network error: Connection refused: getsockopt")) }

    @Test
    fun `a refused fallback surfaces its own connection error, not the primary's`() = runBlocking {
        val client = GoogleTestHttpClient(
            primaryHandler = { Err(ServiceError.RateLimitError("rate limited")) },
            fallbackHandler = refusedFallback
        )
        val service = createService(client, AtomicLong(0))

        val error = service.translate(request).getError()

        // The error the user sees is the fallback's connection-level failure itself. It is a
        // NetworkError, distinct from the primary's rate limit and from any provider HTTP
        // response, and the message is not rewritten into a cause it does not have.
        assertIs<ServiceError.NetworkError>(error)
        assertEquals("Network error: Connection refused: getsockopt", error.message)
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
        assertEquals(fallbackErrors[0], dnsError.message)

        // A connection-level fallback failure never feeds the circuit: the cooldown opened by
        // the primary's rate limit still runs out, admits a probe, and the probe's failure is
        // the primary's own rate limit reopening it. The next request still reaches the
        // fallback, whose reset failure surfaces as itself.
        clock.set(2_000)
        val resetError = service.translate(request).getError()
        assertIs<ServiceError.NetworkError>(resetError)
        assertEquals(fallbackErrors[1], resetError.message)
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

    private companion object {
        const val PRIMARY_JSON = """{"sentences":[{"trans":"Bonjour","orig":"Hello"}],"src":"en"}"""
        const val FALLBACK_FLAT = """["Bonjour","en"]"""
        const val OFFICIAL_JSON = """{"data":{"translations":[{"translatedText":"Bonjour","detectedSourceLanguage":"en"}]}}"""
    }
}
