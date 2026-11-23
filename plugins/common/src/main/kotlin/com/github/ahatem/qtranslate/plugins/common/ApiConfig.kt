package com.github.ahatem.qtranslate.plugins.common

/** Standard request headers for the browser-facing endpoints plugins call. */
object ApiConfig {
    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val DEFAULT_HEADERS = mapOf(
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept-Encoding" to "gzip, deflate"
    )

    /** The default headers and a random browser User-Agent, overridden by [additionalHeaders]. */
    fun createHeaders(additionalHeaders: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        putAll(DEFAULT_HEADERS)
        put("User-Agent", USER_AGENTS.random())
        putAll(additionalHeaders)
    }

    /** [createHeaders] for a JSON request and response. */
    fun createJsonHeaders(additionalHeaders: Map<String, String> = emptyMap()): Map<String, String> =
        createHeaders(
            buildMap {
                put("Content-Type", "application/json")
                put("Accept", "application/json")
                putAll(additionalHeaders)
            }
        )
}
