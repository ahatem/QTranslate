package com.github.ahatem.qtranslate.plugins.common

/**
 * Configuration for API requests with header builders.
 */
data class ApiConfig(
    val defaultUserAgents: List<String> = DEFAULT_USER_AGENTS,
    val defaultTimeoutSeconds: Int = 30,
    val defaultHeaders: Map<String, String> = DEFAULT_HEADERS
) {
    /**
     * Creates standard headers for web requests
     */
    fun createHeaders(
        additionalHeaders: Map<String, String> = emptyMap(),
        randomizeUserAgent: Boolean = true
    ): Map<String, String> = buildMap {
        putAll(defaultHeaders)
        if (randomizeUserAgent) {
            put("User-Agent", randomUserAgent())
        }
        putAll(additionalHeaders)
    }

    /**
     * Creates headers for JSON API requests
     */
    fun createJsonHeaders(
        additionalHeaders: Map<String, String> = emptyMap()
    ): Map<String, String> = createHeaders(
        additionalHeaders = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            putAll(additionalHeaders)
        }
    )

    private fun randomUserAgent(): String = defaultUserAgents.random()

    companion object {
        val DEFAULT_USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        val DEFAULT_HEADERS = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "gzip, deflate"
        )
    }
}