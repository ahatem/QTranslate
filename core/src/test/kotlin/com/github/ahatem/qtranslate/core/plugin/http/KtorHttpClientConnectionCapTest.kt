package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.core.testing.SilentLogger
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
 * That the connection cap constrains real sockets. [io.ktor.client.engine.mock.MockEngine] has no
 * pool, so the server here is real and counts concurrent connections. CIO's per-route cap is
 * advisory (a burst can overshoot it), so this asserts that a higher cap admits more concurrency
 * rather than a hard ceiling.
 */
class KtorHttpClientConnectionCapTest {


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
                logger = SilentLogger,
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
    fun `a higher cap lets more through at once`() {
        val low = peakConcurrency(cap = 2, requests = 12)
        val high = peakConcurrency(cap = 8, requests = 12)

        assertTrue(
            high > low,
            "raising the cap from 2 to 8 should raise concurrency, but saw $low then $high"
        )
    }
}
