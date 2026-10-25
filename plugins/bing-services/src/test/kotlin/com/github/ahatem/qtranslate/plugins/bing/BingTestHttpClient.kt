package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.yield

/**
 * Answers the auth request and the translate request with scripted bodies.
 *
 * [authPage] holds the markup [BingAuthManager] scrapes its token from, since a test that reaches
 * the translate call has to satisfy that first; [authPageWith] scripts a page with different
 * credentials so a test can tell a refreshed token from the original one. Translate answers come
 * from a queue: the last one repeats once the queue is empty, so a test that scripts one body
 * keeps getting it however many calls the service under test makes.
 */
internal class BingTestHttpClient(
    translateResult: Result<String, ServiceError>
) : TextHttpClient() {

    /** Bodies to answer successive translate calls with, oldest first. */
    val translateQueue = ArrayDeque(listOf(translateResult))

    /** What the auth page scrape returns. A failing page scripts a failing token refresh. */
    var authPage: Result<String, ServiceError> = Ok(AUTH_PAGE)

    /**
     * What every fetch from the second one on returns, when a test needs the page to change or
     * fail after the initial token was obtained. Left null, [authPage] serves every fetch.
     */
    var authPageAfterFirst: Result<String, ServiceError>? = null

    var translateCalls: Int = 0
        private set

    /** How many times the Bing translator page (the token source) was fetched. */
    var authFetches: Int = 0
        private set

    /** The form the last translate call posted, so tests can assert the codes it carries. */
    var lastTranslateForm: Map<String, String> = emptyMap()
        private set

    /** Set by a test that needs concurrent getAuth calls to actually overlap. */
    var yieldOnAuthFetch: Boolean = false

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        authFetches++
        if (yieldOnAuthFetch) yield()
        return if (authFetches == 1) authPage else authPageAfterFirst ?: authPage
    }

    override suspend fun postForm(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>,
        cookies: Map<String, String>
    ): Result<String, ServiceError> {
        translateCalls++
        lastTranslateForm = formData
        // The final scripted body repeats, so a queue of one keeps answering every call.
        return if (translateQueue.size > 1) translateQueue.removeFirst() else translateQueue.first()
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> {
        translateCalls++
        return if (translateQueue.size > 1) translateQueue.removeFirst() else translateQueue.first()
    }

    companion object {
        const val IG = "A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1"
        const val TOKEN = "abcdef0123456789"
        const val KEY = "1705000000000"
        const val MUID = "test-muid"

        /**
         * Page markup holding the three things the auth manager scrapes out of it, with the
         * credentials as parameters. The token values double as leak sentinels: tests assert
         * they never reach a user-facing message or a log.
         */
        fun authPage(token: String = TOKEN, key: String = KEY, ig: String = IG): String =
            "<html><body>IG:\"$ig\"" +
                "<div data-iid=\"translator.5023\"></div>" +
                "<script>params_AbusePreventionHelper = [\"$key\",\"$token\"];</script>" +
                "<script>{\"muid\":\"$MUID\",\"sid\":\"test-sid\",\"tid\":\"test-tid\"}</script>" +
                "</body></html>"

        val AUTH_PAGE = authPage()

        /** A plausible translate success, for tests that do not care about the exact text. */
        fun successArray(): Result<String, ServiceError> = Ok(SUCCESS_BODY)

        const val SUCCESS_BODY = """
            [
              {
                "detectedLanguage": {"language":"en","score":1.0},
                "translations": [
                  {"text":"Hola","to":"es"},
                  {"text":", ","to":"es"},
                  {"text": "¿cómo estás?","to":"es"}
                ]
              }
            ]
        """
    }
}
