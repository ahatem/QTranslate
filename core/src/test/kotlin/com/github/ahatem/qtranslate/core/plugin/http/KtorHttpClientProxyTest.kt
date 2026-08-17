package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.core.Logger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That the proxy credentials actually leave the machine.
 *
 * Two levels, because one tool cannot see both. [MockEngine] replaces the engine outright, so it
 * never opens a socket and never sends a CONNECT: it can prove the header is on the request, which
 * is the part this code owns, and nothing about tunnelling. The CONNECT itself is checked against a
 * real socket pretending to be a proxy, which is the only way to read the bytes CIO actually
 * writes. Taking that on trust is what put a wrong claim about this in the notes once already.
 */
class KtorHttpClientProxyTest {

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun okEngine() = MockEngine { respond(content = "ok", status = HttpStatusCode.OK) }

    // ── What this code owns: the header on the request ────────────────────────

    @Test
    fun `proxy credentials are attached to every request`() = runBlocking {
        val engine = okEngine()
        KtorHttpClient(
            logger = silentLogger,
            config = HttpClientConfig(
                enableRetry = false,
                proxy = ProxyConfiguration("http://proxy.invalid:3128", "alice", "s3cret")
            ),
            engine = engine,
            ownsEngine = false
        ).use {
            it.get("https://example.invalid/one")
            it.post("https://example.invalid/two", body = "{}")
        }

        val expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
        assertEquals(2, engine.requestHistory.size)
        engine.requestHistory.forEach { request ->
            assertEquals(
                expected,
                request.headers[HttpHeaders.ProxyAuthorization],
                "every request must carry the credentials, not just the first"
            )
        }
    }

    @Test
    fun `a proxy without credentials sends no authorization header`() = runBlocking {
        val engine = okEngine()
        KtorHttpClient(
            logger = silentLogger,
            config = HttpClientConfig(
                enableRetry = false,
                proxy = ProxyConfiguration("http://proxy.invalid:3128")
            ),
            engine = engine,
            ownsEngine = false
        ).use { it.get("https://example.invalid/one") }

        // An empty Basic header is not the same as no header: it invites a 407 from a proxy that
        // would otherwise have let the request through unauthenticated.
        assertNull(engine.requestHistory.single().headers[HttpHeaders.ProxyAuthorization])
    }

    @Test
    fun `no proxy configured sends no authorization header`() = runBlocking {
        val engine = okEngine()
        KtorHttpClient(
            logger = silentLogger,
            config = HttpClientConfig(enableRetry = false),
            engine = engine,
            ownsEngine = false
        ).use { it.get("https://example.invalid/one") }

        assertNull(engine.requestHistory.single().headers[HttpHeaders.ProxyAuthorization])
    }

    @Test
    fun `an empty username is treated as no credentials`() {
        assertNull(ProxyConfiguration("http://p:1", username = "").authorizationHeader())
        assertNull(ProxyConfiguration("http://p:1", username = null).authorizationHeader())
    }

    @Test
    fun `a password-less account still authenticates`() {
        // Some proxies key on the username alone. "user:" is the correct encoding of that, and
        // dropping the colon would produce a header the proxy cannot parse.
        val header = ProxyConfiguration("http://p:1", username = "alice").authorizationHeader()
        val decoded = String(Base64.getDecoder().decode(header!!.removePrefix("Basic ")))
        assertEquals("alice:", decoded)
    }

    // ── What only a socket can see: the CONNECT ───────────────────────────────

    /**
     * Reads the CONNECT request CIO writes to a real proxy socket.
     *
     * The tunnel is never completed: the assertion is about the bytes CIO sends, and answering
     * would mean speaking TLS. The client's own request therefore fails, which is expected and
     * ignored. What matters is the request line and headers the proxy saw.
     */
    @Test
    fun `Proxy-Authorization is forwarded onto the CONNECT for an HTTPS request`() = runBlocking {
        ServerSocket(0).use { proxy ->
            var seen: String? = null

            val accepting = launch(Dispatchers.IO) {
                runCatching {
                    proxy.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val head = buildString {
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                appendLine(line)
                            }
                        }
                        seen = head
                    }
                }
            }

            val client = KtorHttpClient(
                logger = silentLogger,
                config = HttpClientConfig(
                    enableRetry = false,
                    connectTimeoutMillis = 2_000,
                    requestTimeoutMillis = 3_000,
                    socketTimeoutMillis = 2_000,
                    proxy = ProxyConfiguration(
                        "http://127.0.0.1:${proxy.localPort}", "alice", "s3cret"
                    )
                )
            )
            // Fails once the tunnel is not completed. The proxy's view is the subject here.
            withTimeoutOrNull(8_000) {
                withContext(Dispatchers.IO) { client.get("https://example.invalid/anything") }
            }
            client.close()
            withTimeoutOrNull(3_000) { accepting.join() }

            val request = assertNotNull(seen, "the proxy was never contacted")
            assertTrue(
                request.startsWith("CONNECT example.invalid:443"),
                "expected a CONNECT to the target host, got:\n$request"
            )
            val expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
            assertTrue(
                request.contains("Proxy-Authorization: $expected"),
                "CONNECT must carry the credentials, got:\n$request"
            )
        }
    }
}
