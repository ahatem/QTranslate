package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.shared.logging.CapturingLogger
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The transport must not log URLs, provider bodies, or anything derived from them.
 *
 * Providers receive the request URL — including any query string — and answer with bodies that
 * can echo the request. A log line that interpolates either one writes user text, credentials
 * and provider content into a file that is meant to be attachable to a bug report. The
 * diagnostics that remain are the status code and the request method: enough to correlate with
 * a provider's own logs, nothing that identifies what was translated.
 */
class KtorHttpClientPrivacyTest {

    private val sentinelKey = "SENTINEL-ZQ42-KEY-8f1a"
    private val sentinelUrl = "https://provider.invalid/translate?key=$sentinelKey&q=hello"
    private val sentinelBody = "SENTINEL-ZQ42-BODY-77c3"

    private fun client(engine: MockEngine, logger: CapturingLogger) = KtorHttpClient(
        logger = logger,
        config = HttpClientConfig(enableRetry = false),
        engine = engine,
        ownsEngine = false
    )

    @Test
    fun `a server error logs the status code without the URL or the provider body`() = runBlocking {
        val logger = CapturingLogger()
        val engine = MockEngine { respond(content = sentinelBody, status = HttpStatusCode.InternalServerError) }
        client(engine, logger).use { http ->
            val result: Result<String, ServiceError> = http.get(sentinelUrl, emptyMap(), emptyMap())
            val failure = result.fold(
                success = { fail("a 500 response must surface as a failure, not a success") },
                failure = { it }
            )
            assertTrue(failure is ServiceError.ServiceUnavailableError)
        }

        assertClean(logger)
        assertTrue(
            logger.messages.any { it.contains("status=500") },
            "the status code is still logged: ${logger.messages}"
        )
    }

    @Test
    fun `a payment-required response logs the status code without the URL or the provider body`() = runBlocking {
        val logger = CapturingLogger()
        val engine = MockEngine { respond(content = sentinelBody, status = HttpStatusCode.PaymentRequired) }
        client(engine, logger).use { http ->
            val result: Result<String, ServiceError> = http.post(sentinelUrl, emptyMap(), "{}", emptyMap())
            val failure = result.fold(
                success = { fail("a 402 response must surface as a failure, not a success") },
                failure = { it }
            )
            assertTrue(failure is ServiceError.AuthenticationError)
        }

        assertClean(logger)
        assertTrue(
            logger.messages.any { it.contains("status=402") },
            "the status code is still logged: ${logger.messages}"
        )
    }

    @Test
    fun `a successful response logs nothing about the body or the URL`() = runBlocking {
        val logger = CapturingLogger()
        val engine = MockEngine { respond(content = sentinelBody, status = HttpStatusCode.OK) }
        client(engine, logger).use { http ->
            val result: Result<String, ServiceError> = http.post(sentinelUrl, emptyMap(), "{}", emptyMap())
            assertTrue(result.isOk, "a 200 response still succeeds: $result")
        }

        assertClean(logger)
    }

    private fun assertClean(logger: CapturingLogger) {
        val captured = logger.messages + logger.loggedThrowables
        val leaked = captured.filter { it.contains("SENTINEL-ZQ42") || it.contains("provider.invalid") }
        assertTrue(leaked.isEmpty(), "log lines must not carry URLs, keys or bodies; captured: $leaked")
    }
}
