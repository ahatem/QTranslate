package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.shared.logging.CapturingLogger
import com.github.michaelbull.result.Result
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A cancelled request must surface [CancellationException] to its caller, never a
 * [ServiceError].
 *
 * Every request method catches [Exception] to map transport failures onto a typed
 * [ServiceError], and [CancellationException] is an [Exception]: without an explicit
 * re-throw, a cancellation landing inside a request or response path can complete as
 * "network error" or "no body" instead of being cancelled. Callers that own cancellation —
 * translation timeouts, superseded requests, app shutdown — would then report a provider
 * failure for a request they cancelled themselves, and anything that reads the result before
 * the cancellation is observed (a fallback attempt, a circuit breaker, a completed deferred)
 * acts on a failure the provider never produced. The response path is where the swallow is
 * directly observable: the body reads suspend, and a blanket [runCatching] around them turns
 * a cancelled read into an ordinary failure result.
 */
class KtorHttpClientCancellationTest {

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun client(engine: MockEngine, logger: Logger = silentLogger) = KtorHttpClient(
        logger = logger,
        config = HttpClientConfig(enableRetry = false),
        engine = engine,
        ownsEngine = false
    )

    /** An engine that acknowledges the request and then never answers. */
    private fun hangingEngine(entered: CompletableDeferred<Unit>) = MockEngine {
        entered.complete(Unit)
        CompletableDeferred<Unit>().await()
        respond("never reached")
    }

    /**
     * Cancels a request parked inside the engine and observes what the request produced.
     *
     * The caller itself is not cancelled, so a [ServiceError] the transport returned for a
     * request it cancelled is observable here — that is the failure this class pins down.
     */
    private fun cancelledRequestProducesNothing(
        request: suspend (KtorHttpClient) -> Result<Any, ServiceError>
    ) = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Result<Any, ServiceError>>()
        val http = client(hangingEngine(entered))
        val requestJob = launch(Dispatchers.IO) {
            @Suppress("UNCHECKED_CAST")
            completed.complete(request(http) as Result<Any, ServiceError>)
        }
        entered.await()
        requestJob.cancel()
        // Grace for a transport that wrongly reports the cancellation as a failure.
        delay(100)
        assertFalse(completed.isCompleted, "a cancelled request must not surface as a ServiceError")
        assertTrue(requestJob.isCancelled)
    }

    @Test
    fun `a cancelled POST surfaces cancellation, not a network error`() {
        cancelledRequestProducesNothing { it.post("https://provider.invalid/translate", emptyMap(), "{}", emptyMap()) }
    }

    @Test
    fun `a cancelled GET surfaces cancellation, not a network error`() {
        cancelledRequestProducesNothing { it.get("https://provider.invalid/languages", emptyMap(), emptyMap()) }
    }

    @Test
    fun `a cancelled form POST surfaces cancellation, not a network error`() {
        cancelledRequestProducesNothing {
            it.postForm("https://provider.invalid/translate", emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
    }

    @Test
    fun `a cancelled form-bytes POST surfaces cancellation, not a network error`() {
        cancelledRequestProducesNothing {
            it.postFormBytes("https://provider.invalid/translate", emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
    }

    @Test
    fun `a cancelled GET-bytes request surfaces cancellation, not a network error`() {
        cancelledRequestProducesNothing { it.getBytes("https://provider.invalid/audio", emptyMap(), emptyMap()) }
    }

    @Test
    fun `a cancelled bytes POST surfaces cancellation, not a network error`() {
        cancelledRequestProducesNothing { it.postBytes("https://provider.invalid/upload", emptyMap(), ByteArray(0), emptyMap()) }
    }

    @Test
    fun `a cancellation during the error-body read surfaces cancellation, not a service error`() = runBlocking {
        val responded = CompletableDeferred<Unit>()
        // A 500 whose body never arrives: the error branch reads the body, and that read
        // suspends — cancellation lands inside the response path.
        val engine = MockEngine {
            val response = respond(content = ByteChannel(true), status = HttpStatusCode.InternalServerError)
            responded.complete(Unit)
            response
        }
        val logger = CapturingLogger()
        client(engine, logger).use { http ->
            val call = async(Dispatchers.IO) { http.get("https://provider.invalid/translate", emptyMap(), emptyMap()) }
            responded.await()
            // Real time for the IO thread to park inside the never-arriving body read.
            delay(200)
            call.cancel()
            assertFailsWith<CancellationException> { call.await() }
            assertTrue(
                logger.messages.none { it.contains("request failed") },
                "a cancelled request must not be reported as a failed request: ${logger.messages}"
            )
        }
    }

    @Test
    fun `a cancellation during the success-body read surfaces cancellation, not a network error`() = runBlocking {
        val responded = CompletableDeferred<Unit>()
        val engine = MockEngine {
            val response = respond(content = ByteChannel(true), status = HttpStatusCode.OK)
            responded.complete(Unit)
            response
        }
        val logger = CapturingLogger()
        client(engine, logger).use { http ->
            val call = async(Dispatchers.IO) { http.post("https://provider.invalid/translate", emptyMap(), "{}", emptyMap()) }
            responded.await()
            delay(200)
            call.cancel()
            assertFailsWith<CancellationException> { call.await() }
            assertTrue(
                logger.messages.none { it.contains("request failed") },
                "a cancelled request must not be reported as a failed request: ${logger.messages}"
            )
        }
    }
}
