package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.shared.logging.CapturingLogger
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The transport must not log URLs, provider bodies, or anything derived from them. Only the status
 * code and the request method are allowed through.
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

    private class Case(
        val label: String,
        val status: HttpStatusCode,
        val send: suspend (KtorHttpClient) -> Result<String, ServiceError>,
        val failure: KClass<out ServiceError>?
    )

    @Test
    fun `responses log the status code but never the URL or the provider body`() = runBlocking {
        val cases = listOf(
            Case(
                "server error", HttpStatusCode.InternalServerError,
                { it.get(sentinelUrl, emptyMap(), emptyMap()) }, ServiceError.ServiceUnavailableError::class
            ),
            Case(
                "payment required", HttpStatusCode.PaymentRequired,
                { it.post(sentinelUrl, emptyMap(), "{}", emptyMap()) }, ServiceError.AuthenticationError::class
            ),
            Case("success", HttpStatusCode.OK, { it.post(sentinelUrl, emptyMap(), "{}", emptyMap()) }, null)
        )
        for (case in cases) {
            val logger = CapturingLogger()
            val engine = MockEngine { respond(content = sentinelBody, status = case.status) }
            client(engine, logger).use { http ->
                val result = case.send(http)
                if (case.failure == null) {
                    assertTrue(result.isOk, "${case.label}: a 200 response still succeeds: $result")
                } else {
                    assertIs<ServiceError>(result.getError(), case.label)
                    assertTrue(case.failure.isInstance(result.getError()), "${case.label}: got ${result.getError()}")
                }
            }

            val captured = logger.messages + logger.loggedThrowables
            val leaked = captured.filter { it.contains("SENTINEL-ZQ42") || it.contains("provider.invalid") }
            assertTrue(leaked.isEmpty(), "${case.label}: log lines must not carry URLs, keys or bodies; captured: $leaked")
            if (case.failure != null) {
                assertTrue(
                    logger.messages.any { it.contains("status=${case.status.value}") },
                    "${case.label}: the status code is still logged: ${logger.messages}"
                )
            }
        }
    }
}
