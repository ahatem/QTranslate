package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.CapturingLogger
import com.github.michaelbull.result.fold
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Bing answers a translation request with an error object under HTTP 200, and with interstitial
 * HTML when the session is bad. Both bodies can echo the request — including the text being
 * translated — so the parser's diagnostics may only say which shape it saw, never what was in
 * it. The parser already refuses to log bodies; these tests pin that contract with sentinels.
 */
class BingResponseParserPrivacyTest {

    private val sentinel = "SENTINEL-ZQ42-BING-3a77"

    @Test
    fun `a malformed JSON body is logged without the body`() {
        val logger = CapturingLogger()
        val result = BingResponseParser(logger).parseTranslations("{ \"$sentinel\": ")

        val error = result.fold(success = { fail("a malformed body must fail") }, failure = { it })
        assertTrue(error is ServiceError.InvalidResponseError)
        assertClean(logger)
    }

    @Test
    fun `a non-JSON body is logged without the body`() {
        val logger = CapturingLogger()
        val result = BingResponseParser(logger).parseTranslations("<html>$sentinel interstitial</html>")

        val error = result.fold(success = { fail("an interstitial page must fail") }, failure = { it })
        assertTrue(error is ServiceError.ServiceUnavailableError)
        assertClean(logger)
    }

    @Test
    fun `a provider rejection logs the status, not the provider's error text`() {
        val logger = CapturingLogger()
        val result = BingResponseParser(logger).parseTranslations(
            """{"statusCode": "401", "errorMessage": "$sentinel token expired"}"""
        )

        val error = result.fold(success = { fail("a rejection must fail") }, failure = { it })
        assertTrue(error is ServiceError.AuthenticationError)
        assertContains(error.message, "rejected")
        assertClean(logger)
    }

    @Test
    fun `a well-formed translation array still parses`() {
        val logger = CapturingLogger()
        val result = BingResponseParser(logger).parseTranslations("[]")

        assertTrue(result.fold(success = { it.isEmpty() }, failure = { false }))
        assertClean(logger)
    }

    private fun assertClean(logger: CapturingLogger) {
        val captured = logger.messages + logger.loggedThrowables
        val leaked = captured.filter { it.contains(sentinel) }
        assertTrue(leaked.isEmpty(), "sentinel content leaked into logs: $leaked")
    }
}
