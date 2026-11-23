package com.github.ahatem.qtranslate.core.plugin.http

import com.github.ahatem.qtranslate.core.settings.data.NetworkConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * That what the settings page stores becomes what the client sends with.
 *
 * The seam where a number a person typed turns into a number Ktor obeys, which is exactly where a
 * factor of a thousand goes missing without anything failing to compile.
 */
class NetworkConfigMappingTest {

    @Test
    fun `seconds become milliseconds`() {
        val mapped = NetworkConfig(
            requestTimeoutSeconds = 45,
            connectTimeoutSeconds = 8,
            socketTimeoutSeconds = 20
        ).toHttpClientConfig()

        assertEquals(45_000, mapped.requestTimeoutMillis)
        assertEquals(8_000, mapped.connectTimeoutMillis)
        assertEquals(20_000, mapped.socketTimeoutMillis)
    }

    @Test
    fun `a proxy that is switched off or has no address is no proxy at all`() {
        // The address is kept in the settings so switching the proxy back on does not mean typing
        // it again, so "off" must be honoured rather than inferred from a blank.
        val switchedOff = NetworkConfig(
            proxyEnabled = false,
            proxyUrl = "http://proxy.example:3128",
            proxyUsername = "alice"
        ).toHttpClientConfig(proxyPassword = "s3cret")
        assertNull(switchedOff.proxy, "switched off")

        // Sending every request to nowhere is a worse failure than the switch appearing inert.
        assertNull(NetworkConfig(proxyEnabled = true, proxyUrl = "   ").toHttpClientConfig().proxy, "no address")
    }

    @Test
    fun `the proxy password comes from the secret store, not the config file`() {
        val stored = NetworkConfig(
            proxyEnabled = true,
            proxyUrl = "http://proxy.example:3128",
            proxyUsername = "alice"
        )
        val mapped = stored.toHttpClientConfig(proxyPassword = "s3cret")

        assertEquals("alice", mapped.proxy?.username)
        assertEquals("s3cret", mapped.proxy?.password)
    }

    @Test
    fun `a username is trimmed and an empty one means unauthenticated`() {
        val named = NetworkConfig(proxyEnabled = true, proxyUrl = "http://p:1", proxyUsername = "  alice  ")
            .toHttpClientConfig(proxyPassword = "x")
        assertEquals("alice", named.proxy?.username)

        val anonymous = NetworkConfig(proxyEnabled = true, proxyUrl = "http://p:1", proxyUsername = "   ")
            .toHttpClientConfig()
        assertNull(anonymous.proxy?.username)
        assertNull(anonymous.proxy?.authorizationHeader(), "no username means no credentials header")
    }

    @Test
    fun `per-host entries set the request timeout and leave the rest shared`() {
        val mapped = NetworkConfig(
            requestTimeoutSeconds = 30,
            connectTimeoutSeconds = 15,
            hostTimeoutSeconds = mapOf("127.0.0.1" to 120)
        ).toHttpClientConfig()

        val local = mapped.hostTimeouts.getValue("127.0.0.1")
        assertEquals(120_000, local.requestTimeoutMillis)
        // What is slow about a local model is how long it thinks, not the network to it. Leaving
        // these unset keeps the shared values rather than inventing per-host ones.
        assertNull(local.connectTimeoutMillis)
        assertNull(local.socketTimeoutMillis)
    }

    @Test
    fun `blank host keys are dropped`() {
        val mapped = NetworkConfig(hostTimeoutSeconds = mapOf("" to 60, "  " to 60, "ok.invalid" to 60))
            .toHttpClientConfig()

        // A row left half-filled in the settings table would otherwise become an entry matching
        // nothing, which is harmless but shows up later as a mystery.
        assertEquals(setOf("ok.invalid"), mapped.hostTimeouts.keys)
    }

    @Test
    fun `nonsense values are clamped rather than passed through`() {
        val mapped = NetworkConfig(
            requestTimeoutSeconds = 0,
            connectTimeoutSeconds = -5,
            socketTimeoutSeconds = 0,
            maxRetries = 99,
            maxConnectionsPerHost = 0,
            maxConnectionsTotal = 100_000,
            hostTimeoutSeconds = mapOf("h" to 0)
        ).toHttpClientConfig()

        // Ktor rejects a non-positive timeout by throwing, so a zero typed into a spinner would
        // take the whole client down rather than being ignored.
        assertEquals(1_000, mapped.requestTimeoutMillis)
        assertEquals(1_000, mapped.connectTimeoutMillis)
        assertEquals(1_000, mapped.socketTimeoutMillis)
        assertEquals(1_000, mapped.hostTimeouts.getValue("h").requestTimeoutMillis)
        assertEquals(10, mapped.maxRetries)
        assertEquals(1, mapped.maxConnectionsPerHost)
        assertEquals(512, mapped.maxConnectionsTotal)
    }
}
