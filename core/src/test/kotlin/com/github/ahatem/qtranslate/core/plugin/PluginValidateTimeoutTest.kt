package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the relationship between a connection test's outer cap and the network timeouts the
 * request inside it is subject to.
 *
 * The cap used to be a flat 15 seconds — shorter than the default 30-second request timeout —
 * so a correctly configured endpoint that answered inside its own request timeout still failed
 * the test with "Validation did not answer within 15s". Real use of the same service worked.
 * The cap now never sits below what one check could legitimately take; these tests keep it
 * from slipping back under the user's own settings.
 */
class PluginValidateTimeoutTest {

    @Test
    fun `default settings give the check room for every retry the client may spend`() {
        // Request timeout 30s, 2 retries with 1s/2s backoff, plus headroom.
        assertEquals(30_000L * 3 + 3_000L + 2_000L, validateTimeoutMs(NetworkConfig()))
    }

    @Test
    fun `the floor holds when the network is quicker than it`() {
        // A 5-second request under no retries finishes well inside the 15s floor.
        assertEquals(15_000L, validateTimeoutMs(NetworkConfig(requestTimeoutSeconds = 5, retryEnabled = false)))
    }

    @Test
    fun `a disabled retry policy budgets a single attempt plus headroom`() {
        assertEquals(30_000L + 2_000L, validateTimeoutMs(NetworkConfig(retryEnabled = false)))
    }

    @Test
    fun `zero retries budget a single attempt even with retry enabled`() {
        assertEquals(30_000L + 2_000L, validateTimeoutMs(NetworkConfig(maxRetries = 0)))
    }

    @Test
    fun `a per-host override raises the ceiling with it`() {
        val config = NetworkConfig(
            requestTimeoutSeconds = 30,
            hostTimeoutSeconds = mapOf("openrouter.ai" to 90, "127.0.0.1" to 300)
        )
        // The largest override, not the global value, bounds a check against that host.
        assertEquals(300_000L * 3 + 3_000L + 2_000L, validateTimeoutMs(config))
    }

    @Test
    fun `clamped retry settings behave like the client's clamped ones`() {
        // maxRetries clamps to 10; a zero initial delay clamps to 1s, matching the mapping.
        val config = NetworkConfig(maxRetries = 99, retryInitialDelaySeconds = 0, requestTimeoutSeconds = 10)
        val expectedBackoff = (1 + 2 + 4 + 8 + 16 + 32 + 64 + 128 + 256 + 512) * 1_000L
        assertEquals(10_000L * 11 + expectedBackoff + 2_000L, validateTimeoutMs(config))
    }

    @Test
    fun `the cap is never below the request timeout`() {
        // Whichever way the settings are shaped, a request the client would still allow must
        // not be declared failed by the outer watcher.
        listOf(
            NetworkConfig(),
            NetworkConfig(requestTimeoutSeconds = 1),
            NetworkConfig(requestTimeoutSeconds = 120, retryEnabled = false),
            NetworkConfig(hostTimeoutSeconds = mapOf("slow.example" to 45)),
        ).forEach { config ->
            val singleAttemptMs = maxOf(
                config.requestTimeoutSeconds,
                config.hostTimeoutSeconds.values.maxOrNull() ?: 0
            ).coerceAtLeast(1) * 1_000L
            assertTrue(
                validateTimeoutMs(config) >= singleAttemptMs,
                "cap ${validateTimeoutMs(config)} must cover a $singleAttemptMs ms request"
            )
        }
    }
}
