package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

/**
 * Answers the auth request and the translate request with scripted bodies.
 *
 * [AUTH_PAGE] holds the markup [BingAuthManager] scrapes its token from, since a test that reaches
 * the translate call has to satisfy that first.
 */
internal class BingTestHttpClient(
    private val translateResult: Result<String, ServiceError>
) : TextHttpClient() {

    var translateCalls: Int = 0
        private set

    /** The form the last translate call posted, so tests can assert the codes it carries. */
    var lastTranslateForm: Map<String, String> = emptyMap()
        private set

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = Ok(AUTH_PAGE)

    override suspend fun postForm(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>,
        cookies: Map<String, String>
    ): Result<String, ServiceError> {
        translateCalls++
        lastTranslateForm = formData
        return translateResult
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = translateResult

    private companion object {
        /** Plausible page markup holding the three things the auth manager scrapes out of it. */
        val AUTH_PAGE = "<html><body>IG:\"A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1\"" +
            "<div data-iid=\"translator.5023\"></div>" +
            "<script>params_AbusePreventionHelper = [\"1705000000000\",\"abcdef0123456789\"];</script>" +
            "<script>{\"muid\":\"test-muid\",\"sid\":\"test-sid\",\"tid\":\"test-tid\"}</script>" +
            "</body></html>"
    }
}
