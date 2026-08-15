package com.github.ahatem.qtranslate.plugins.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins which endpoints are treated as local, because that decision alone decides whether the
 * plugin demands an API key.
 *
 * Getting it wrong in one direction makes a local model impossible to use; in the other it lets a
 * hosted endpoint through with no key and reports the resulting 401 as a rejected key rather than
 * a missing one.
 */
class AISettingsTest {

    private fun settings(url: String, key: String = "") =
        AISettings(baseUrl = url, apiKey = key)

    @Test
    fun `local endpoints need no key`() {
        listOf(
            "http://localhost:11434/v1",          // Ollama
            "http://127.0.0.1:1234/v1",           // LM Studio
            "http://192.168.1.50:11434/v1",       // another machine on the LAN
            "http://10.0.0.4:8080/v1",
            "http://172.16.3.9:8080/v1",
            "http://workstation.local:11434/v1",
        ).forEach { url ->
            assertTrue(settings(url).isLocalEndpoint, "$url should count as local")
            assertNull(settings(url).missingKeyError(), "$url should not demand a key")
        }
    }

    @Test
    fun `hosted endpoints demand a key`() {
        listOf(
            "https://openrouter.ai/api/v1",
            "https://api.openai.com/v1",
            "https://api.mistral.ai/v1",
            "https://generativelanguage.googleapis.com/v1beta/openai",
        ).forEach { url ->
            assertFalse(settings(url).isLocalEndpoint, "$url should not count as local")
            assertNotNull(settings(url).missingKeyError(), "$url should demand a key")
        }
    }

    @Test
    fun `a key set for a hosted endpoint is no longer missing`() {
        assertNull(settings("https://openrouter.ai/api/v1", key = "sk-test").missingKeyError())
    }

    /**
     * `172.16` through `172.31` are private; `172.32` and above are not.
     *
     * The obvious `startsWith("172.")` would wave through a public address, which is the case
     * worth pinning since nothing else would notice.
     */
    @Test
    fun `only the private half of the 172 range is local`() {
        assertTrue(settings("http://172.16.0.1:8080/v1").isLocalEndpoint)
        assertTrue(settings("http://172.31.255.1:8080/v1").isLocalEndpoint)
        assertFalse(settings("http://172.32.0.1:8080/v1").isLocalEndpoint)
        assertFalse(settings("http://172.8.0.1:8080/v1").isLocalEndpoint)
    }

    @Test
    fun `a malformed url is treated as hosted rather than crashing`() {
        val broken = settings("not a url at all")
        assertFalse(broken.isLocalEndpoint)
        assertNotNull(broken.missingKeyError())
    }
}
