package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.core.Logger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which timeouts a request actually goes out with.
 *
 * Read off the capability attached to the request rather than by waiting for one to fire: the
 * question here is what was configured, and a test that proves it by hanging for thirty seconds
 * proves the same thing far more slowly. That a timeout fires at all is Ktor's business and is
 * already its own tested behaviour.
 */
class KtorHttpClientTimeoutTest {

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun okEngine() = MockEngine { respond(content = "ok", status = HttpStatusCode.OK) }

    private fun HttpRequestData.timeouts(): HttpTimeoutConfig? =
        getCapabilityOrNull(HttpTimeoutCapability)

    private fun clientWith(config: HttpClientConfig, engine: MockEngine) = KtorHttpClient(
        logger = silentLogger,
        config = config,
        engine = engine,
        ownsEngine = false
    )

    @Test
    fun `the client-wide timeouts are what an unlisted host gets`() = runBlocking {
        val engine = okEngine()
        clientWith(
            HttpClientConfig(
                enableRetry = false,
                requestTimeoutMillis = 11_000,
                connectTimeoutMillis = 12_000,
                socketTimeoutMillis = 13_000
            ),
            engine
        ).use { it.get("https://unlisted.invalid/thing") }

        val sent = engine.requestHistory.single().timeouts()
        assertEquals(11_000, sent?.requestTimeoutMillis)
        assertEquals(12_000, sent?.connectTimeoutMillis)
        assertEquals(13_000, sent?.socketTimeoutMillis)
    }

    @Test
    fun `a listed host gets its own timeouts instead`() = runBlocking {
        val engine = okEngine()
        clientWith(
            HttpClientConfig(
                enableRetry = false,
                requestTimeoutMillis = 11_000,
                connectTimeoutMillis = 12_000,
                socketTimeoutMillis = 13_000,
                hostTimeouts = mapOf(
                    "127.0.0.1" to HostTimeout(
                        requestTimeoutMillis = 120_000,
                        connectTimeoutMillis = 2_000,
                        socketTimeoutMillis = 120_000
                    )
                )
            ),
            engine
        ).use { it.post("http://127.0.0.1:11434/api/generate", body = "{}") }

        // A local model can think for a long time before its first token, and is quick to refuse a
        // connection when it is not running at all.
        val sent = engine.requestHistory.single().timeouts()
        assertEquals(120_000, sent?.requestTimeoutMillis)
        assertEquals(2_000, sent?.connectTimeoutMillis)
        assertEquals(120_000, sent?.socketTimeoutMillis)
    }

    @Test
    fun `an override names one timeout and leaves the others alone`() = runBlocking {
        val engine = okEngine()
        clientWith(
            HttpClientConfig(
                enableRetry = false,
                requestTimeoutMillis = 11_000,
                connectTimeoutMillis = 12_000,
                socketTimeoutMillis = 13_000,
                hostTimeouts = mapOf("slow.invalid" to HostTimeout(socketTimeoutMillis = 90_000))
            ),
            engine
        ).use { it.get("https://slow.invalid/thing") }

        // The whole reason the fields are nullable. Mentioning a host to lengthen one timeout must
        // not quietly reset the two it said nothing about.
        val sent = engine.requestHistory.single().timeouts()
        assertEquals(90_000, sent?.socketTimeoutMillis, "the named one is applied")
        assertEquals(11_000, sent?.requestTimeoutMillis, "the unnamed ones keep the shared value")
        assertEquals(12_000, sent?.connectTimeoutMillis, "the unnamed ones keep the shared value")
    }

    @Test
    fun `host matching ignores case, port and scheme`() = runBlocking {
        val engine = okEngine()
        clientWith(
            HttpClientConfig(
                enableRetry = false,
                requestTimeoutMillis = 11_000,
                hostTimeouts = mapOf("API.Example.INVALID" to HostTimeout(requestTimeoutMillis = 45_000))
            ),
            engine
        ).use { it.get("https://api.example.invalid:8443/v1/translate") }

        assertEquals(45_000, engine.requestHistory.single().timeouts()?.requestTimeoutMillis)
    }

    @Test
    fun `a host entry does not leak onto a different host`() = runBlocking {
        val engine = okEngine()
        clientWith(
            HttpClientConfig(
                enableRetry = false,
                requestTimeoutMillis = 11_000,
                hostTimeouts = mapOf("127.0.0.1" to HostTimeout(requestTimeoutMillis = 120_000))
            ),
            engine
        ).use {
            it.get("http://127.0.0.1:11434/a")
            it.get("https://api.example.invalid/b")
        }

        val (local, remote) = engine.requestHistory
        assertEquals(120_000, local.timeouts()?.requestTimeoutMillis)
        assertEquals(11_000, remote.timeouts()?.requestTimeoutMillis, "the remote host keeps the shared value")
    }

    @Test
    fun `a subdomain is not matched by its parent`() = runBlocking {
        val engine = okEngine()
        clientWith(
            HttpClientConfig(
                enableRetry = false,
                requestTimeoutMillis = 11_000,
                hostTimeouts = mapOf("example.invalid" to HostTimeout(requestTimeoutMillis = 45_000))
            ),
            engine
        ).use { it.get("https://api.example.invalid/thing") }

        // Exact matching, deliberately. Suffix matching would make "example.invalid" silently
        // capture "evil-example.invalid" too, and the entry is a name rather than a pattern.
        assertEquals(11_000, engine.requestHistory.single().timeouts()?.requestTimeoutMillis)
    }

    @Test
    fun `no per-host entries means nothing is attached beyond the client defaults`() = runBlocking {
        val engine = okEngine()
        clientWith(HttpClientConfig(enableRetry = false), engine)
            .use { it.get("https://example.invalid/thing") }

        assertEquals(30_000, engine.requestHistory.single().timeouts()?.requestTimeoutMillis)
        assertNull(HostTimeout().requestTimeoutMillis, "an empty override names nothing")
    }
}
