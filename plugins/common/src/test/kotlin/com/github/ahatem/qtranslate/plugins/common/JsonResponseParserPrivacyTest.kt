package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.getError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shared parser is the last line before a provider body becomes a [ServiceError]: its
 * diagnostics must not carry the body it failed to read. A serialization exception can quote
 * the input it choked on, and handing the throwable to the logger prints that into the log.
 */
class JsonResponseParserPrivacyTest {

    private val sentinel = "SENTINEL-ZQ42-JSON-5f39"

    @Test
    fun `a body the deserializer rejects is logged without the body`() {
        val logger = CapturingLogger()
        val parser = JsonResponseParser<String>(FakePluginContext(logger)) { _ ->
            throw SerializationException("Expected string but found: $sentinel")
        }

        val failure = runBlocking { parser.parse("raw provider body") }.getError()

        assertContains(assertNotNull(failure).message, "Failed to parse JSON")
        assertClean(logger)
    }

    @Test
    fun `any other parsing exception is logged without its text`() {
        val logger = CapturingLogger()
        val parser = JsonResponseParser<String>(FakePluginContext(logger)) { _ ->
            throw IllegalStateException("Provider echoed: $sentinel")
        }

        val failure = runBlocking { parser.parse("raw provider body") }.getError()

        assertContains(assertNotNull(failure).message, "Unexpected parsing error")
        assertClean(logger)
    }

    private fun assertClean(logger: CapturingLogger) {
        val leaked = (logger.messages + logger.loggedThrowables).filter { it.contains(sentinel) }
        assertTrue(leaked.isEmpty(), "sentinel content leaked into logs: $leaked")
    }
}
