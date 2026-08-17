package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.core.Logger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the retry rule is allowed to send twice.
 *
 * These are the cases that cost money when they are wrong and show nothing when they are: a
 * replayed POST is a second translation billed and a second unit of quota spent, and from the
 * outside it looks exactly like one request that worked. The engine is scripted so the number of
 * requests that actually reached the wire is a counted fact rather than an inference.
 */
class KtorHttpClientRetryTest {

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    /** Answers with [status] every time, and counts how often it was asked. */
    private fun countingEngine(
        status: HttpStatusCode,
        calls: AtomicInteger,
        retryAfterSeconds: Long? = null
    ) = MockEngine {
        calls.incrementAndGet()
        respond(
            content = "body",
            status = status,
            headers = retryAfterSeconds
                ?.let { headersOf(HttpHeaders.RetryAfter, it.toString()) }
                ?: headersOf()
        )
    }

    private fun clientOn(engine: MockEngine, maxRetries: Int = 2) = KtorHttpClient(
        logger = silentLogger,
        config = HttpClientConfig(enableRetry = true, maxRetries = maxRetries),
        engine = engine,
        ownsEngine = false
    )

    @Test
    fun `a POST answered with 500 is sent exactly once`() = runTest {
        val calls = AtomicInteger()
        val engine = countingEngine(HttpStatusCode.InternalServerError, calls)
        clientOn(engine).use { it.post("https://example.invalid/translate", body = "{}") }

        // The dangerous case. A 5xx can mean the server did the work and failed to report it, so
        // sending the POST again translates the text a second time and bills for both.
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

        // 429 says the request was refused rather than performed, so there is nothing to
        // duplicate. It is also the status a translation API actually returns under load, and the
        // rule this replaced excluded it entirely.
        assertEquals(3, calls.get(), "429 should be retried even for a POST")
    }

    @Test
    fun `Retry-After is waited out rather than backed off past`() = runTest {
        val calls = AtomicInteger()
        val engine = countingEngine(HttpStatusCode.TooManyRequests, calls, retryAfterSeconds = 5)

        // Measured on the clock rather than the test scheduler: the client sends inside
        // withContext(Dispatchers.IO), which leaves runTest's virtual time behind, so this delay
        // is a real one. Five seconds sits clearly above what backoff alone asks for at the first
        // retry, one second plus up to one of jitter, so a pass here cannot be explained by the
        // backoff having been slow anyway. That makes this the slow test in the file, on purpose.
        val startedAt = System.nanoTime()
        clientOn(engine, maxRetries = 1).use { it.get("https://example.invalid/languages") }
        val waitedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(2, calls.get(), "expected the original attempt and one retry")
        assertTrue(
            waitedMillis >= 4_000,
            "expected to honour the server's Retry-After of 5s, but waited only ${waitedMillis}ms"
        )
    }

    @Test
    fun `retry can be turned off entirely`() = runTest {
        val calls = AtomicInteger()
        val engine = countingEngine(HttpStatusCode.InternalServerError, calls)
        KtorHttpClient(
            logger = silentLogger,
            config = HttpClientConfig(enableRetry = false),
            engine = engine,
            ownsEngine = false
        ).use { it.get("https://example.invalid/languages") }

        assertEquals(1, calls.get())
    }

    @Test
    fun `the method actually sent is the one asked for`() = runTest {
        val calls = AtomicInteger()
        val engine = countingEngine(HttpStatusCode.OK, calls)
        clientOn(engine).use { it.post("https://example.invalid/translate", body = "{}") }

        assertEquals(HttpMethod.Post, engine.requestHistory.single().method)
    }

    @Test
    fun `the configured first delay is what is actually waited`() = runTest {
        val calls = AtomicInteger()
        val engine = countingEngine(HttpStatusCode.TooManyRequests, calls)

        // Real clock again, for the same reason: the send happens on Dispatchers.IO, outside the
        // test scheduler. Three seconds is far enough above the one-second default that a pass
        // cannot be the default having been used instead.
        val client = KtorHttpClient(
            logger = silentLogger,
            config = HttpClientConfig(
                enableRetry = true,
                maxRetries = 1,
                retryInitialDelayMillis = 3_000
            ),
            engine = engine,
            ownsEngine = false
        )
        val startedAt = System.nanoTime()
        client.use { it.get("https://example.invalid/languages") }
        val waitedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(2, calls.get())
        assertTrue(
            waitedMillis >= 2_500,
            "expected to wait the configured 3s before retrying, waited only ${waitedMillis}ms"
        )
    }
}
