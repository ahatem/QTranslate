package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the [AiProfile] identity, endpoint normalization, and preset lookup rules that
 * Agents B and C build on. Hosted/local classification is pinned here in addition to
 * [AISettingsTest] so the rule is verified wherever profiles are consumed.
 *
 * No test here touches the network or logs a real key; every key is a synthetic fixture.
 */
class AiProfilesTest {

    private fun profile(
        baseUrl: String = "https://openrouter.ai/api/v1",
        apiKey: String = "sk-test",
        model: String = "openrouter/free",
        customHeaders: String = """{"X-Title": "QTranslate"}""",
        preset: String = "openrouter",
        temperature: Double = 0.3,
        maxTokens: Int = 4096
    ) = AiProfile(
        preset = preset,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        customHeaders = customHeaders,
        temperature = temperature,
        maxTokens = maxTokens
    )

    // -------------------------------------------------------------------------
    // isLocalEndpoint / missingKeyError on AiProfile
    // -------------------------------------------------------------------------

    @Test
    fun `localhost and private-network endpoints are local, hosted endpoints are not`() {
        listOf(
            "http://localhost:11434/v1",
            "http://127.0.0.1:1234/v1",
            "http://192.168.1.50:11434/v1",
            "http://10.0.0.4:8080/v1",
            "http://172.16.3.9:8080/v1",
            "http://workstation.local:11434/v1"
        ).forEach { url ->
            assertTrue(profile(baseUrl = url, apiKey = "").isLocalEndpoint, "$url should be local")
            assertNull(profile(baseUrl = url, apiKey = "").missingKeyError(), "$url should not demand a key")
        }
        listOf(
            "https://openrouter.ai/api/v1",
            "https://api.openai.com/v1",
            "https://172.32.0.1:8080/v1"
        ).forEach { url ->
            assertFalse(profile(baseUrl = url, apiKey = "").isLocalEndpoint, "$url should be hosted")
            assertTrue(profile(baseUrl = url, apiKey = "").missingKeyError() is ServiceError.ConfigurationError,
                "$url without a key should report a configuration error")
        }
    }

    @Test
    fun `only the private half of the 172 range is local`() {
        assertTrue(profile(baseUrl = "http://172.16.0.1:8080/v1").isLocalEndpoint)
        assertTrue(profile(baseUrl = "http://172.31.255.1:8080/v1").isLocalEndpoint)
        assertFalse(profile(baseUrl = "http://172.32.0.1:8080/v1").isLocalEndpoint)
        assertFalse(profile(baseUrl = "http://172.8.0.1:8080/v1").isLocalEndpoint)
    }

    @Test
    fun `a malformed url is treated as hosted rather than crashing`() {
        val broken = profile(baseUrl = "not a url at all", apiKey = "")
        assertFalse(broken.isLocalEndpoint)
        assertTrue(broken.missingKeyError() is ServiceError.ConfigurationError)
    }

    @Test
    fun `a hosted profile with a key needs no further setup`() {
        assertNull(profile(baseUrl = "https://openrouter.ai/api/v1", apiKey = "sk-test").missingKeyError())
    }

    // -------------------------------------------------------------------------
    // AiEndpoints
    // -------------------------------------------------------------------------

    @Test
    fun `normalizedBaseUrl trims whitespace and trailing slashes`() {
        assertEquals("https://openrouter.ai/api/v1", AiEndpoints.normalizedBaseUrl("  https://openrouter.ai/api/v1  "))
        assertEquals("https://openrouter.ai/api/v1", AiEndpoints.normalizedBaseUrl("https://openrouter.ai/api/v1/"))
        assertEquals("https://openrouter.ai/api/v1", AiEndpoints.normalizedBaseUrl("https://openrouter.ai/api/v1//"))
        assertEquals("", AiEndpoints.normalizedBaseUrl(""))
        assertEquals("", AiEndpoints.normalizedBaseUrl("   "))
    }

    @Test
    fun `normalizedBaseUrl strips a chat-completions suffix case-insensitively`() {
        assertEquals("https://openrouter.ai/api/v1", AiEndpoints.normalizedBaseUrl("https://openrouter.ai/api/v1/chat/completions"))
        assertEquals("https://openrouter.ai/api/v1", AiEndpoints.normalizedBaseUrl("https://openrouter.ai/api/v1/chat/completions/"))
        assertEquals("https://openrouter.ai/api/v1", AiEndpoints.normalizedBaseUrl("https://openrouter.ai/api/v1/CHAT/COMPLETIONS"))
        assertEquals("https://openrouter.ai/api/v1", AiEndpoints.normalizedBaseUrl("  https://openrouter.ai/api/v1/chat/completions/  "))
    }

    @Test
    fun `normalizedBaseUrl keeps an unrelated path segment that only looks similar`() {
        assertEquals("https://host/chat/completions-extra", AiEndpoints.normalizedBaseUrl("https://host/chat/completions-extra"))
    }

    @Test
    fun `chatCompletionsUrl is idempotent`() {
        listOf(
            "https://openrouter.ai/api/v1",
            "https://openrouter.ai/api/v1/",
            "https://openrouter.ai/api/v1/chat/completions",
            "https://openrouter.ai/api/v1/chat/completions/",
            "  https://openrouter.ai/api/v1/CHAT/COMPLETIONS  "
        ).forEach { raw ->
            val once = AiEndpoints.chatCompletionsUrl(raw)
            assertEquals("https://openrouter.ai/api/v1/chat/completions", once)
            assertEquals(once, AiEndpoints.chatCompletionsUrl(once), "second pass must not append again")
            assertEquals("https://openrouter.ai/api/v1", AiEndpoints.normalizedBaseUrl(once))
        }
    }

    @Test
    fun `AiProfile chatCompletionsUrl never doubles the suffix`() {
        val p = profile(baseUrl = "https://api.openai.com/v1/chat/completions")
        assertEquals("https://api.openai.com/v1/chat/completions", p.chatCompletionsUrl())
        assertEquals(AiEndpoints.chatCompletionsUrl("https://api.openai.com/v1"), p.chatCompletionsUrl())
    }

    // -------------------------------------------------------------------------
    // AiProviderPresets
    // -------------------------------------------------------------------------

    @Test
    fun `preset default base URLs match the provider contract`() {
        assertEquals("https://openrouter.ai/api/v1", AiProviderPresets.defaultBaseUrl("openrouter"))
        assertEquals("https://api.openai.com/v1", AiProviderPresets.defaultBaseUrl("openai"))
        assertEquals("http://localhost:11434/v1", AiProviderPresets.defaultBaseUrl("local"))
        assertEquals("", AiProviderPresets.defaultBaseUrl("custom"), "custom has no default address")
    }

    @Test
    fun `unknown and null presets have no default base URL`() {
        assertNull(AiProviderPresets.defaultBaseUrl("mistral"))
        assertNull(AiProviderPresets.defaultBaseUrl(null))
        assertNull(AiProviderPresets.defaultBaseUrl(""))
    }

    @Test
    fun `PRESETS includes the hosted, local, and custom entries with unique ids`() {
        val ids = AiProviderPresets.PRESETS.map { it.id }
        assertEquals(listOf("openrouter", "openai", "local", "custom"), ids)
    }

    // -------------------------------------------------------------------------
    // identityKey
    // -------------------------------------------------------------------------

    @Test
    fun `identical profiles share an identity key`() {
        assertEquals(profile().identityKey, profile().identityKey)
        assertEquals(
            profile(baseUrl = "https://openrouter.ai/api/v1/").identityKey,
            profile(baseUrl = "https://openrouter.ai/api/v1").identityKey,
            "trailing slashes must not change identity"
        )
        assertEquals(
            profile(baseUrl = "https://openrouter.ai/api/v1/chat/completions").identityKey,
            profile(baseUrl = "https://openrouter.ai/api/v1").identityKey
        )
    }

    @Test
    fun `different keys, models, endpoints, or budgets produce different identity keys`() {
        val base = profile()
        assertNotEquals(base.identityKey, profile(apiKey = "sk-other").identityKey)
        assertNotEquals(base.identityKey, profile(model = "gpt-4o").identityKey)
        assertNotEquals(base.identityKey, profile(baseUrl = "https://api.openai.com/v1").identityKey)
        assertNotEquals(base.identityKey, profile(temperature = 0.7).identityKey)
        assertNotEquals(base.identityKey, profile(maxTokens = 8192).identityKey)
    }

    @Test
    fun `header key order does not change identity`() {
        val a = profile(customHeaders = """{"B": "2", "A": "1"}""")
        val b = profile(customHeaders = """{"A": "1", "B": "2"}""")
        assertEquals(a.identityKey, b.identityKey)
        assertEquals(normalizedHeaders("""{"B": "2", "A": "1"}"""), normalizedHeaders("""{"A": "1", "B": "2"}"""))
    }

    @Test
    fun `identityKey never contains the raw key`() {
        val secret = "sk-super-secret-12345"
        val key = profile(apiKey = secret).identityKey
        assertFalse(key.contains(secret))
        assertFalse(key.contains("super-secret"))
    }

    @Test
    fun `identityKey is stable across construction`() {
        val p = profile()
        assertEquals(p.identityKey, p.identityKey)
        assertEquals(p, p.copy())
    }

    // -------------------------------------------------------------------------
    // Custom headers validation and normalization
    // -------------------------------------------------------------------------

    @Test
    fun `blank custom headers are valid and normalize to empty`() {
        assertTrue(isCustomHeadersValid(""))
        assertTrue(isCustomHeadersValid("   "))
        assertEquals("", normalizedHeaders(""))
        assertEquals("", normalizedHeaders("   "))
    }

    @Test
    fun `a JSON string map is valid and the default attribution headers pass`() {
        val defaultHeaders = AISettings().customHeaders
        assertTrue(isCustomHeadersValid(defaultHeaders))
        assertTrue(isCustomHeadersValid("""{"X-Custom": "1"}"""))
        assertTrue(isCustomHeadersValid("{}"))
    }

    @Test
    fun `non-object or non-string-valued custom headers are invalid`() {
        assertFalse(isCustomHeadersValid("[\"X-Custom\"]"))
        assertFalse(isCustomHeadersValid("""{"X-Custom": 42}"""))
        assertFalse(isCustomHeadersValid("""{"X-Custom": {"nested": "1"}}"""))
        assertFalse(isCustomHeadersValid("not json at all"))
        assertFalse(isCustomHeadersValid("{\"X-Custom\": "))
    }

    @Test
    fun `normalizedHeaders sorts keys deterministically`() {
        assertEquals("A=1;B=2", normalizedHeaders("""{"B": "2", "A": "1"}"""))
        assertEquals("A=1;B=2", normalizedHeaders("""{"A": "1", "B": "2"}"""))
        // Malformed input is left as the trimmed raw value: deterministic, still
        // distinguishable from valid input via isCustomHeadersValid.
        assertEquals("not json", normalizedHeaders("  not json  "))
    }
}
