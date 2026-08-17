package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * That the connection cap really constrains real sockets, and by how much.
 *
 * [io.ktor.client.engine.mock.MockEngine] cannot answer this: it never opens a socket, so it has
 * no pool to cap and would pass whatever the setting said. CIO enforces the limit per route with a
 * counter on each endpoint, which is only observable in how many connections a server sees at
 * once, so the server here is real and its job is to count.
 *
 * ### The cap is advisory, and these tests say so
 * `Endpoint.makePipelineRequest` reads `connections.value`, compares it to
 * `maxConnectionsPerRoute`, and only increments the counter later inside `connect`, with a suspend
 * boundary in between. Several coroutines can pass the same stale check before any of them
 * increments, so a burst can overshoot the number. This was found by asserting a hard ceiling of
 * three and watching four connections arrive.
 *
 * These tests therefore assert what the setting actually buys — concurrency held near the cap and
 * far below the number of requests in flight — rather than a guarantee CIO does not make. Pinning
 * the exact overshoot would only encode one machine's timing.
 */
class KtorHttpClientConnectionCapTest {

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    /**
     * Fires [requests] at a counting server with the client capped at [cap], and reports the most
     * connections that were ever open at the same time.
     */
    private fun peakConcurrency(cap: Int, requests: Int): Int = runBlocking {
        val open = AtomicInteger()
        val peak = AtomicInteger()

        ServerSocket(0).use { server ->
            val accepting = launch(Dispatchers.IO) {
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketException) {
                        break // closed at the end of the test
                    }
                    launch(Dispatchers.IO) {
                        val now = open.incrementAndGet()
                        peak.updateAndGet { maxOf(it, now) }
                        try {
                            socket.use {
                                val reader = it.getInputStream().bufferedReader()
                                while (true) {
                                    val line = reader.readLine() ?: return@use
                                    if (line.isEmpty()) break
                                }
                                // Held open so the requests genuinely overlap. Answering instantly
                                // would let each finish before the next began, and a cap of one
                                // would then look identical to a cap of ten.
                                Thread.sleep(150)
                                it.getOutputStream().apply {
                                    write(
                                        ("HTTP/1.1 200 OK\r\n" +
                                            "Content-Length: 2\r\n" +
                                            "Connection: close\r\n\r\nok").toByteArray()
                                    )
                                    flush()
                                }
                            }
                        } finally {
                            open.decrementAndGet()
                        }
                    }
                }
            }

            val client = KtorHttpClient(
                logger = silentLogger,
                config = HttpClientConfig(
                    enableRetry = false,
                    requestTimeoutMillis = 20_000,
                    connectTimeoutMillis = 5_000,
                    socketTimeoutMillis = 20_000,
                    maxConnectionsPerHost = cap
                )
            )
            val url = "http://127.0.0.1:${server.localPort}/thing"
            withTimeoutOrNull(30_000) {
                (1..requests).map { async(Dispatchers.IO) { client.get(url) } }.awaitAll()
            }
            client.close()
            accepting.cancel()
        }
        peak.get()
    }

    @Test
    fun `a low cap holds concurrency far below the number of requests in flight`() {
        val peak = peakConcurrency(cap = 3, requests = 12)

        assertTrue(peak > 0, "the server was never contacted")
        // Twelve requests were launched at once. Without a cap they would all land together, so
        // anything close to the cap is the setting doing its job. The allowance above it is the
        // documented race, not slack for a broken limit.
        assertTrue(peak <= 6, "a cap of 3 should keep concurrency near 3, but the server saw $peak")
        assertTrue(peak < 12, "the cap did nothing: all 12 requests were open at once")
    }

    @Test
    fun `a higher cap lets more through at once`() {
        val low = peakConcurrency(cap = 2, requests = 12)
        val high = peakConcurrency(cap = 8, requests = 12)

        // The paired half. Without it, a client that throttled everything to a single connection
        // would satisfy "concurrency stayed low" and look correct.
        assertTrue(
            high > low,
            "raising the cap from 2 to 8 should raise concurrency, but saw $low then $high"
        )
    }
}
