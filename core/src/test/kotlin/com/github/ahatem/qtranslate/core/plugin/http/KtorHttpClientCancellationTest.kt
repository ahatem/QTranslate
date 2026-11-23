package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.shared.logging.CapturingLogger
import com.github.ahatem.qtranslate.core.testing.SilentLogger
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
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A cancelled request must surface [CancellationException] to its caller, never a [ServiceError].
 * Every request method catches [Exception] to map transport failures, and [CancellationException]
 * is one, so a swallowed cancellation would be reported as a provider failure.
 */
class KtorHttpClientCancellationTest {

    private fun client(engine: MockEngine, logger: Logger = SilentLogger) = KtorHttpClient(
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
     * Cancels a request parked inside the engine and observes what the request produced. The
     * caller itself is not cancelled, so a [ServiceError] returned for a cancelled request is
     * observable here.
     */
    private fun cancelledRequestProducesNothing(
        label: String,
        request: suspend (KtorHttpClient) -> Result<Any, ServiceError>
    ) = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Result<Any, ServiceError>>()
        val http = client(hangingEngine(entered))
        val requestJob = launch(Dispatchers.IO) {
            completed.complete(request(http))
        }
        entered.await()
        requestJob.cancel()
        // Grace for a transport that wrongly reports the cancellation as a failure.
        delay(100)
        assertFalse(completed.isCompleted, "$label: a cancelled request must not surface as a ServiceError")
        assertTrue(requestJob.isCancelled, label)
    }

    private data class BodyCase(
        val label: String,
        val status: HttpStatusCode,
        val request: suspend (KtorHttpClient) -> Result<Any, ServiceError>
    )

    private fun method(
        label: String,
        request: suspend (KtorHttpClient) -> Result<Any, ServiceError>
    ) = label to request

    @Test
    fun `a cancelled request surfaces cancellation, not a network error, for every method`() {
        val methods = listOf(
            method("POST") { it.post("https://provider.invalid/translate", emptyMap(), "{}", emptyMap()) },
            method("GET") { it.get("https://provider.invalid/languages", emptyMap(), emptyMap()) },
            method("form POST") {
                it.postForm("https://provider.invalid/translate", emptyMap(), emptyMap(), emptyMap(), emptyMap())
            },
            method("form-bytes POST") {
                it.postFormBytes("https://provider.invalid/translate", emptyMap(), emptyMap(), emptyMap(), emptyMap())
            },
            method("GET bytes") { it.getBytes("https://provider.invalid/audio", emptyMap(), emptyMap()) },
            method("bytes POST") { it.postBytes("https://provider.invalid/upload", emptyMap(), ByteArray(0), emptyMap()) }
        )
        for ((label, request) in methods) cancelledRequestProducesNothing(label, request)
    }

    @Test
    fun `a cancellation during the body read surfaces cancellation, not a service error`() = runBlocking {
        // A body that never arrives: the read suspends, so cancellation lands inside the response path.
        val cases = listOf(
            BodyCase("error body", HttpStatusCode.InternalServerError) {
                it.get("https://provider.invalid/translate", emptyMap(), emptyMap())
            },
            BodyCase("success body", HttpStatusCode.OK) {
                it.post("https://provider.invalid/translate", emptyMap(), "{}", emptyMap())
            }
        )
        for ((label, status, request) in cases) {
            val responded = CompletableDeferred<Unit>()
            val engine = MockEngine {
                val response = respond(content = ByteChannel(true), status = status)
                responded.complete(Unit)
                response
            }
            val logger = CapturingLogger()
            client(engine, logger).use { http ->
                val call = async(Dispatchers.IO) { request(http) }
                responded.await()
                // Real time for the IO thread to park inside the never-arriving body read.
                delay(200)
                call.cancel()
                assertFailsWith<CancellationException>(label) { call.await() }
                assertTrue(
                    logger.messages.none { it.contains("request failed") },
                    "$label: a cancelled request must not be reported as failed: ${logger.messages}"
                )
            }
        }
    }
}
