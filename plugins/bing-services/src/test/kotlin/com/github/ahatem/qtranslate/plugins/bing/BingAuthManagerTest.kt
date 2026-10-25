package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.getError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the token cache lifecycle: caching within the nominal lifetime, refresh on expiry,
 * single-flight refresh under a concurrent burst, and invalidation of a token the endpoint
 * has rejected. Issue 366: a token Bing stopped accepting stayed cached for the full hour,
 * failing every translation until restart.
 */
class BingAuthManagerTest {

    /** A controllable clock, so the one hour expiry is a fact of the test, not of wall time. */
    private var clockMillis: Long = 1_000_000L

    private fun authManager(client: BingTestHttpClient) = BingAuthManager(
        FakePluginContext(),
        client,
        now = { clockMillis }
    )

    /** The token a call produced, failing the test if it did not. */
    private suspend fun BingAuthManager.token(): BingAuth = getAuth().fold(
        success = { it },
        failure = { error -> throw AssertionError("expected a token: ${error.message}") }
    )

    @Test
    fun `a fetched token is cached for the nominal hour`() = runBlocking {
        val client = BingTestHttpClient(BingTestHttpClient.successArray())
        val manager = authManager(client)

        val first = manager.token()
        clockMillis += 59 * 60 * 1000L
        val stillCached = manager.token()

        assertEquals(first, stillCached)
        assertEquals(1, client.authFetches, "a token inside its lifetime must not be refetched")
    }

    @Test
    fun `an expired token is refreshed`() = runBlocking {
        val client = BingTestHttpClient(BingTestHttpClient.successArray())
        client.authPage = Ok(BingTestHttpClient.authPage(token = FIRST_TOKEN))
        val manager = authManager(client)

        val first = manager.token()
        clockMillis += 60 * 60 * 1000L
        client.authPage = Ok(BingTestHttpClient.authPage(token = SECOND_TOKEN))
        val refreshed = manager.token()

        assertEquals(2, client.authFetches)
        assertNotEquals(first.token, refreshed.token)
    }

    @Test
    fun `a concurrent burst refreshes the page once`() = runBlocking {
        val client = BingTestHttpClient(BingTestHttpClient.successArray()).apply {
            // Makes the fetch suspend, so every caller in the burst reaches the cache check
            // before the first one finishes. Without it a single-threaded dispatcher would run
            // the callers one at a time and the test would prove nothing about single-flight.
            yieldOnAuthFetch = true
        }
        val manager = authManager(client)

        val tokens = (1..5).map { async { manager.getAuth() } }.awaitAll()

        assertEquals(1, client.authFetches, "each caller in the burst fetched the page itself")
        tokens.forEach { token ->
            assertNull(token.getError(), "every caller in the burst must get a token")
        }
    }

    @Test
    fun `an invalidated token is not served again`() = runBlocking {
        val client = BingTestHttpClient(BingTestHttpClient.successArray()).apply {
            authPage = Ok(BingTestHttpClient.authPage(token = FIRST_TOKEN))
        }
        val manager = authManager(client)

        val rejected = manager.token()
        manager.invalidateIfCurrent(rejected)

        client.authPage = Ok(BingTestHttpClient.authPage(token = SECOND_TOKEN))
        val fresh = manager.token()

        assertEquals(2, client.authFetches, "invalidation must force a fresh fetch")
        assertEquals(SECOND_TOKEN, fresh.token)
    }

    @Test
    fun `invalidation keeps a newer token that has not failed`() = runBlocking {
        val client = BingTestHttpClient(BingTestHttpClient.successArray()).apply {
            authPage = Ok(BingTestHttpClient.authPage(token = FIRST_TOKEN))
        }
        val manager = authManager(client)

        val stale = manager.token()
        clockMillis += 60 * 60 * 1000L
        client.authPage = Ok(BingTestHttpClient.authPage(token = SECOND_TOKEN))
        val current = manager.token()

        manager.invalidateIfCurrent(stale)
        val after = manager.token()

        assertEquals(2, client.authFetches, "a newer token must survive invalidating the old one")
        assertEquals(SECOND_TOKEN, after.token)
        assertEquals(current, after)
    }

    @Test
    fun `a failed refresh leaves no cache and recovers on the next call`() = runBlocking {
        val client = BingTestHttpClient(BingTestHttpClient.successArray())
        val manager = authManager(client)

        client.authPage = Err(ServiceError.NetworkError("GET request failed", null))
        val failure = manager.getAuth().getError()
        clockMillis += 60 * 60 * 1000L

        client.authPage = Ok(BingTestHttpClient.authPage(token = SECOND_TOKEN))
        val recovered = manager.token()

        assertIs<ServiceError.NetworkError>(failure)
        assertEquals(2, client.authFetches)
        assertEquals(SECOND_TOKEN, recovered.token)
    }

    @Test
    fun `a page missing its token fields is an invalid response, not a crash`() = runBlocking {
        val client = BingTestHttpClient(BingTestHttpClient.successArray()).apply {
            authPage = Ok("<html><body>no token here</body></html>")
        }
        val manager = authManager(client)

        val error = manager.getAuth().getError()

        assertIs<ServiceError.InvalidResponseError>(error)
        assertTrue(error.message.isNotBlank())
        assertTrue(!error.message.contains("<html"), "message carried page markup: ${error.message}")
    }

    private companion object {
        const val FIRST_TOKEN = "first-token-value"
        const val SECOND_TOKEN = "second-token-value"
    }
}
