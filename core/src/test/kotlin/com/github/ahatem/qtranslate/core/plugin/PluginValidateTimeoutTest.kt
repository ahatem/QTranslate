package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The connection-test cap must never sit below what one check could legitimately take. */
class PluginValidateTimeoutTest {

    @Test
    fun `the floor holds when the network is quicker than it`() {
        assertEquals(15_000L, validateTimeoutMs(NetworkConfig(requestTimeoutSeconds = 5, retryEnabled = false)))
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
