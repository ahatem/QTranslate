package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.Serializable

/**
 * How the application reaches the network, for every plugin at once.
 *
 * Stored here rather than per plugin because that is the whole point: a proxy or a timeout is a
 * property of where the user is sitting, not of which translation service they happen to be using,
 * and setting it eight times is seven times too many. Plugins are handed a client built from this,
 * so none of them has to know it exists.
 *
 * The proxy password is deliberately absent. Configuration is written to disk as plain JSON, and a
 * password belongs in the secret store next to the API keys. See [proxyPasswordKey].
 */
@Serializable
data class NetworkConfig(
    val proxyEnabled: Boolean = false,
    /** For example `http://proxy.example:3128`. Credentials are separate fields, not userinfo. */
    val proxyUrl: String = "",
    val proxyUsername: String = "",

    val requestTimeoutSeconds: Int = 30,
    val connectTimeoutSeconds: Int = 15,
    val socketTimeoutSeconds: Int = 15,

    val retryEnabled: Boolean = true,
    val maxRetries: Int = 2,
    /**
     * Seconds before the first retry. Each attempt after waits twice the last, plus jitter.
     *
     * One number rather than a written-out ladder: a fixed interval is the wrong answer for
     * a rate limit, and a hand-written 5/10/15 is four more values to get wrong that a
     * server's Retry-After overrides anyway whenever it appears.
     */
    val retryInitialDelaySeconds: Int = 1,

    val maxConnectionsPerHost: Int = 8,
    val maxConnectionsTotal: Int = 64,

    /**
     * Longer timeouts for particular hosts, keyed by hostname.
     *
     * A local model is the case this exists for: it may think for a minute before its first token,
     * where a cloud endpoint that has not answered in ten seconds is not going to.
     */
    val hostTimeoutSeconds: Map<String, Int> = emptyMap()
) {
    companion object {
        /** Where the proxy password lives, in the secret store rather than in this file. */
        const val proxyPasswordKey: String = "network.proxy.password"
    }
}
