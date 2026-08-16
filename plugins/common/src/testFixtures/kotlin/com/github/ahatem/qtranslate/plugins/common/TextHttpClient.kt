package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * Base for test doubles of services that only ever make text requests.
 *
 * Most fakes script `get` and `post` and nothing else. Left to implement the whole [HttpClient]
 * contract each one would carry three stubs it never uses, repeated across every plugin, which is
 * the same drift this module already publishes [FakePluginContext] to avoid.
 *
 * The binary and form calls throw rather than returning an empty success. A fake that quietly
 * answers a call the test did not arrange makes the test pass for the wrong reason, and a service
 * that starts using one of them should fail loudly and get a real double.
 */
abstract class TextHttpClient : HttpClient {

    override suspend fun getBytes(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<ByteArray, ServiceError> = notArranged("getBytes", url)

    override suspend fun postForm(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>,
        cookies: Map<String, String>
    ): Result<String, ServiceError> = notArranged("postForm", url)

    override suspend fun postFormBytes(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>,
        cookies: Map<String, String>
    ): Result<ByteArray, ServiceError> = notArranged("postFormBytes", url)

    private fun notArranged(call: String, url: String): Nothing =
        error("${this::class.simpleName} received an unarranged $call for $url")
}

/**
 * The client a [FakePluginContext] carries when the test did not supply one.
 *
 * Throws on every call. Returning an empty success instead would let a plugin under test make a
 * request nobody arranged and carry on, and returning a network error would be worse still: the
 * test would exercise the plugin's failure path while looking like it had tested the happy one.
 */
object UnreachableHttpClient : TextHttpClient() {

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> =
        error("This test's PluginContext has no HTTP client arranged, but a GET was made to $url")

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> =
        error("This test's PluginContext has no HTTP client arranged, but a POST was made to $url")
}
