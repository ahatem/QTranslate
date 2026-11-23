package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.testing.SilentLogger
import com.github.michaelbull.result.getError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the retry rule may send twice. A replayed POST is a second translation billed and a second
 * unit of quota spent, so the number of requests that reached the wire is counted. Retry delays
 * are recorded rather than slept.
 */
class KtorHttpClientRetryTest {

    private val delays = CopyOnWriteArrayList<Long>()

    private fun countingEngine(
        status: HttpStatusCode,
        calls: AtomicInteger,
        retryAfterHeader: String? = null
    ) = MockEngine {
        calls.incrementAndGet()
        respond(
            content = "body",
            status = status,
            headers = retryAfterHeader?.let { headersOf(HttpHeaders.RetryAfter, it) } ?: headersOf()
        )
    }

    private fun clientOn(
        engine: MockEngine,
        maxRetries: Int = 2,
        enableRetry: Boolean = true,
        retryInitialDelayMillis: Long = 1_000
    ) = KtorHttpClient(
        logger = SilentLogger,
        config = HttpClientConfig(
            enableRetry = enableRetry,
            maxRetries = maxRetries,
            retryInitialDelayMillis = retryInitialDelayMillis
        ),
        engine = engine,
        ownsEngine = false,
        retryDelay = { delays += it }
    )

    private fun httpDateFromNow(seconds: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(seconds))

    @Test
    fun `a POST answered with 500 is sent exactly once`() = runTest {
        val calls = AtomicInteger()
        val engine = countingEngine(HttpStatusCode.InternalServerError, calls)
        clientOn(engine).use { it.post("https://example.invalid/translate", body = "{}") }

        // A 5xx can mean the server did the work and failed to report it.
        assertEquals(1, calls.get(), "a POST must not be replayed after a 5xx")
    }

    @Test
    fun `a GET answered with 500 is retried, because repeating it changes nothing`() = runTest {
        val calls = AtomicInteger()
        val engine = countingEngine(HttpStatusCode.InternalServerError, calls)
        clientOn(engine, maxRetries = 2).use { it.get("https://example.invalid/languages") }

        assertEquals(3, calls.get(), "an idempotent method should still retry: 1 attempt + 2 retries")
    }

    @Test
    fun `a POST answered with 429 is retried, because the server did not act on it`() = runTest {
        val calls = AtomicInteger()
        val engine = countingEngine(HttpStatusCode.TooManyRequests, calls)
        clientOn(engine, maxRetries = 2).use { it.post("https://example.invalid/translate", body = "{}") }

        assertEquals(3, calls.get(), "429 should be retried even for a POST")
    }

    @Test
    fun `Retry-After is waited out rather than backed off past`() = runTest {
        val calls = AtomicInteger()
        val engine = countingEngine(HttpStatusCode.TooManyRequests, calls, retryAfterHeader = "5")
        clientOn(engine, maxRetries = 1).use { it.get("https://example.invalid/languages") }

        assertEquals(2, calls.get(), "expected the original attempt and one retry")
        // Default backoff for the first retry is under two seconds, so 5s can only be Retry-After.
        assertEquals(1, delays.size)
        assertTrue(delays.single() >= 5_000, "expected to honour Retry-After of 5s, requested ${delays.single()}ms")
    }

    @Test
    fun `retry can be turned off entirely`() = runTest {
        val calls = AtomicInteger()
        val engine = countingEngine(HttpStatusCode.InternalServerError, calls)
        clientOn(engine, enableRetry = false).use { it.get("https://example.invalid/languages") }

        assertEquals(1, calls.get())
    }

    @Test
    fun `the configured first delay is what is actually waited`() = runTest {
        val calls = AtomicInteger()
        val engine = countingEngine(HttpStatusCode.TooManyRequests, calls)
        clientOn(engine, maxRetries = 1, retryInitialDelayMillis = 3_000)
            .use { it.get("https://example.invalid/languages") }

        assertEquals(2, calls.get())
        // Far above the one-second default, so the configured value is what was used.
        assertEquals(1, delays.size)
        assertTrue(delays.single() >= 3_000, "expected the configured 3s first delay, requested ${delays.single()}ms")
    }

    @Test
    fun `a 429 reports Retry-After in seconds, or leaves the hint unset when unusable`() = runTest {
        // expected: null means the hint is unset, otherwise the accepted range of reported seconds
        data class Case(val label: String, val header: String?, val expected: IntRange?)

        val cases = listOf(
            Case("seconds", "7", 7..7),
            Case("absent", null, null),
            Case("HTTP-date fixed in the past", "Wed, 21 Oct 2015 07:28:00 GMT", null),
            Case("absurd value is clamped to the bound", "86400", 600..600),
            Case("negative", "-1", null),
            // The date has whole-second resolution, so the conversion may land a second either side.
            Case("HTTP-date in the future", httpDateFromNow(30), 28..31),
            Case("HTTP-date an hour ago", httpDateFromNow(-3_600), null)
        )
        for (case in cases) {
            val engine = countingEngine(HttpStatusCode.TooManyRequests, AtomicInteger(), case.header)
            val error = clientOn(engine, enableRetry = false)
                .use { it.get("https://example.invalid/languages") }
                .getError()

            val reported = assertIs<ServiceError.RateLimitError>(error, case.label).retryAfterSeconds
            if (case.expected == null) {
                assertNull(reported, case.label)
            } else {
                assertNotNull(reported, case.label)
                assertTrue(reported in case.expected, "${case.label}: expected ${case.expected}, got $reported")
            }
        }
    }

    @Test
    fun `a single-attempt request makes one wire attempt and never waits out Retry-After`() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.TooManyRequests, null, "429"),
            Triple(HttpStatusCode.TooManyRequests, "5", "429 with Retry-After"),
            Triple(HttpStatusCode.InternalServerError, null, "5xx")
        )
        for ((status, retryAfter, label) in cases) {
            delays.clear()
            val calls = AtomicInteger()
            val error = clientOn(countingEngine(status, calls, retryAfter), maxRetries = 2).use {
                it.getOnce("https://example.invalid/languages")
            }.getError()

            assertEquals(1, calls.get(), "$label: getOnce must not be retried")
            assertTrue(delays.isEmpty(), "$label: a single attempt must not wait, requested $delays")
            if (status == HttpStatusCode.TooManyRequests) {
                val limit = assertIs<ServiceError.RateLimitError>(error, label)
                assertEquals(retryAfter?.toInt(), limit.retryAfterSeconds, label)
            }
        }
    }
}
