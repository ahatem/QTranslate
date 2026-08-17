package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig

/**
 * Turns what the user set into what the client takes.
 *
 * Two shapes rather than one, deliberately. The stored form is what a settings page can put in
 * front of a person: seconds, an enabled flag beside the thing it enables, a password that lives
 * somewhere else. The client form is what Ktor needs: milliseconds, a proxy that is either fully
 * described or absent. Collapsing them would mean either the settings page dealing in milliseconds
 * or the client carrying a flag it has no use for.
 *
 * @param proxyPassword read from the secret store, since it is not in the configuration file.
 */
fun NetworkConfig.toHttpClientConfig(proxyPassword: String? = null): HttpClientConfig =
    HttpClientConfig(
        requestTimeoutMillis = requestTimeoutSeconds.coerceAtLeast(1).seconds(),
        connectTimeoutMillis = connectTimeoutSeconds.coerceAtLeast(1).seconds(),
        socketTimeoutMillis = socketTimeoutSeconds.coerceAtLeast(1).seconds(),
        enableRetry = retryEnabled,
        maxRetries = maxRetries.coerceIn(0, 10),
        retryInitialDelayMillis = retryInitialDelaySeconds.coerceIn(1, 60).seconds(),
        // Absent unless it is both switched on and actually filled in. A proxy enabled with a blank
        // address would send every request to nowhere, which is a worse outcome than the switch
        // appearing not to work.
        proxy = takeIf { proxyEnabled && proxyUrl.isNotBlank() }?.let {
            ProxyConfiguration(
                url = proxyUrl.trim(),
                username = proxyUsername.trim().takeIf { name -> name.isNotEmpty() },
                password = proxyPassword
            )
        },
        // Only the request timeout is given per host. Connect and socket timeouts describe the
        // network between here and there, which a slow model does not change: what is slow about a
        // local model is how long it thinks, and that is the request.
        hostTimeouts = hostTimeoutSeconds
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, seconds) ->
                HostTimeout(requestTimeoutMillis = seconds.coerceAtLeast(1).seconds())
            },
        maxConnectionsPerHost = maxConnectionsPerHost.coerceIn(1, 64),
        maxConnectionsTotal = maxConnectionsTotal.coerceIn(1, 512)
    )

private fun Int.seconds(): Long = this * 1_000L
