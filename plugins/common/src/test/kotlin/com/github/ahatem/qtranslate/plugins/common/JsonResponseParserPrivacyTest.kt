package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.SecretStore
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SettingsStore
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

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
        val parser = JsonResponseParser<String>(ParserTestContext(logger)) { _ ->
            throw SerializationException("Expected string but found: $sentinel")
        }

        val result = runBlocking { parser.parse("raw provider body") }

        val failure = result.fold(
            success = { fail("a body the deserializer rejects must fail, not succeed") },
            failure = { it }
        )
        assertContains(failure.message, "Failed to parse JSON")
        assertClean(logger)
    }

    @Test
    fun `any other parsing exception is logged without its text`() {
        val logger = CapturingLogger()
        val parser = JsonResponseParser<String>(ParserTestContext(logger)) { _ ->
            throw IllegalStateException("Provider echoed: $sentinel")
        }

        val result = runBlocking { parser.parse("raw provider body") }

        val failure = result.fold(
            success = { fail("a body the deserializer rejects must fail, not succeed") },
            failure = { it }
        )
        assertContains(failure.message, "Unexpected parsing error")
        assertClean(logger)
    }

    @Test
    fun `a readable body still parses`() {
        val parser = JsonResponseParser<String>(ParserTestContext(CapturingLogger())) { it }
        val value = runBlocking { parser.parse("value") }.fold(
            success = { it },
            failure = { fail("a readable body must parse, not fail") }
        )
        assertEquals("value", value)
    }

    private fun assertClean(logger: CapturingLogger) {
        val captured = logger.messages + logger.loggedThrowables
        val leaked = captured.filter { it.contains(sentinel) }
        assertTrue(leaked.isEmpty(), "sentinel content leaked into logs: $leaked")
    }

    /** The parser reads only [PluginContext.logger]; the rest of the context is inert. */
    private class ParserTestContext(override val logger: Logger) : PluginContext {
        override val scope = CoroutineScope(SupervisorJob())
        override val settings = InertSettings
        override val secrets = InertSecrets
        override val http = InertHttpClient
        override suspend fun notify(title: DisplayText, body: DisplayText, type: NotificationType) = Unit
        override fun getPluginDataDirectory() = File(".")
    }

    private object InertSettings : SettingsStore {
        override suspend fun getString(key: String, default: String?, serviceKey: String?) = default
        override suspend fun getInt(key: String, default: Int, serviceKey: String?) = default
        override suspend fun getLong(key: String, default: Long, serviceKey: String?) = default
        override suspend fun getDouble(key: String, default: Double, serviceKey: String?) = default
        override suspend fun getBoolean(key: String, default: Boolean, serviceKey: String?) = default
        override suspend fun put(key: String, value: String, serviceKey: String?) = Unit
        override suspend fun put(key: String, value: Int, serviceKey: String?) = Unit
        override suspend fun put(key: String, value: Long, serviceKey: String?) = Unit
        override suspend fun put(key: String, value: Double, serviceKey: String?) = Unit
        override suspend fun put(key: String, value: Boolean, serviceKey: String?) = Unit
        override suspend fun remove(key: String, serviceKey: String?) = Unit
    }

    private object InertSecrets : SecretStore {
        override suspend fun get(key: String) = null as String?
        override suspend fun put(key: String, value: String) = Unit
        override suspend fun remove(key: String) = Unit
        override suspend fun contains(key: String) = false
    }

    private object InertHttpClient : HttpClient {
        private fun unreachable() = Err(ServiceError.NetworkError("Not arranged in this test"))
        override suspend fun get(url: String, headers: Map<String, String>, queryParams: Map<String, Any?>) =
            unreachable()
        override suspend fun post(url: String, headers: Map<String, String>, body: String?, queryParams: Map<String, Any?>) =
            unreachable()
        override suspend fun getBytes(url: String, headers: Map<String, String>, queryParams: Map<String, Any?>) =
            unreachable()
        override suspend fun postForm(
            url: String, formData: Map<String, String>, headers: Map<String, String>,
            queryParams: Map<String, Any?>, cookies: Map<String, String>
        ) = unreachable()
        override suspend fun postFormBytes(
            url: String, formData: Map<String, String>, headers: Map<String, String>,
            queryParams: Map<String, Any?>, cookies: Map<String, String>
        ) = unreachable()
        override suspend fun postBytes(
            url: String, headers: Map<String, String>, body: ByteArray?, queryParams: Map<String, Any?>
        ) = unreachable()
    }

    private class CapturingLogger : Logger {
        val messages = mutableListOf<String>()
        val loggedThrowables = mutableListOf<String>()

        override fun debug(message: String) {
            messages += message
        }

        override fun info(message: String) {
            messages += message
        }

        override fun warn(message: String) {
            messages += message
        }

        override fun error(message: String, error: Throwable?) {
            messages += message
            error?.let { loggedThrowables += it.toString() }
        }
    }
}
