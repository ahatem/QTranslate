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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        clock: AtomicLong
    ) = GoogleTranslatorService(
        FakePluginContext(),
        GoogleSettings(),
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

    private companion object {
        const val PRIMARY_JSON = """{"sentences":[{"trans":"Bonjour","orig":"Hello"}],"src":"en"}"""
        const val FALLBACK_FLAT = """["Bonjour","en"]"""
    }
}
