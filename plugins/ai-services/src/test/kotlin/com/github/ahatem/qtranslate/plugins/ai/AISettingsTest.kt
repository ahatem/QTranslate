package com.github.ahatem.qtranslate.plugins.ai

import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * The defaults are the configuration every new user starts from, and the ones the OpenRouter
     * setup guides quote back at us. The endpoint is the production chat-completions base (the
     * client appends `/chat/completions`), and the model is OpenRouter's free auto-router alias —
     * both verified against OpenRouter's own documentation and public model list.
     */
    @Test
    fun `defaults match the OpenRouter contract`() {
        val defaults = AISettings()
        assertEquals("https://openrouter.ai/api/v1", defaults.baseUrl)
        assertEquals("openrouter/free", defaults.model)
        assertEquals("openrouter", defaults.preset)
        assertEquals(0.3, defaults.temperature)
        assertEquals(4096, defaults.maxTokens)
    }

    // -------------------------------------------------------------------------
    // profileFor() — per-capability profiles (AI Plugin 2.0)
    // -------------------------------------------------------------------------

    private val allCapabilities = AiCapability.entries

    @Test
    fun `all capabilities inherit the Default profile by default`() {
        val settings = AISettings(apiKey = "sk-default-key")
        val d = settings.defaultProfile()
        allCapabilities.forEach { capability ->
            val profile = settings.profileFor(capability)
            assertEquals(d, profile, "$capability should resolve to the Default profile")
            assertEquals(d.identityKey, profile.identityKey, "$capability identityKey should match the Default")
        }
    }

    @Test
    fun `legacy settings with only Default fields set migrate to inherited profiles`() {
        // Pre-2.0 persisted state: only the legacy Default fields, everything else fresh.
        val legacy = AISettings(
            baseUrl = "https://api.mistral.ai/v1",
            apiKey = "sk-legacy",
            model = "mistral-small-latest",
            temperature = 0.7,
            maxTokens = 8192,
            customHeaders = """{"X-Custom": "1"}"""
        )
        assertTrue(legacy.translationUseDefault)
        assertTrue(legacy.rewriteUseDefault)
        assertTrue(legacy.summarizationUseDefault)
        assertTrue(legacy.spellCheckUseDefault)
        assertTrue(legacy.dictionaryUseDefault)
        assertTrue(legacy.visionUseDefault)
        allCapabilities.forEach { capability ->
            val profile = legacy.profileFor(capability)
            assertEquals("https://api.mistral.ai/v1", profile.baseUrl, "$capability baseUrl")
            assertEquals("sk-legacy", profile.apiKey, "$capability apiKey")
            assertEquals("mistral-small-latest", profile.model, "$capability model")
            assertEquals(0.7, profile.temperature, "$capability temperature")
            assertEquals(8192, profile.maxTokens, "$capability maxTokens")
            assertEquals("""{"X-Custom": "1"}""", profile.customHeaders, "$capability headers")
        }
    }

    @Test
    fun `each capability can independently override every field`() {
        fun AISettings.withOverrides(capability: AiCapability): AISettings = when (capability) {
            AiCapability.TRANSLATION -> copy(
                translationUseDefault = false, translationPreset = "openai",
                translationBaseUrl = "https://api.openai.com/v1", translationApiKey = "sk-translation",
                translationModel = "gpt-4o", translationCustomHeaders = """{"X-Cap": "t"}""",
                translationTemperature = 0.1, translationMaxTokens = 1024
            )
            AiCapability.REWRITE -> copy(
                rewriteUseDefault = false, rewritePreset = "openai",
                rewriteBaseUrl = "https://api.openai.com/v1", rewriteApiKey = "sk-rewrite",
                rewriteModel = "gpt-4o-mini", rewriteCustomHeaders = """{"X-Cap": "r"}""",
                rewriteTemperature = 0.9, rewriteMaxTokens = 2048
            )
            AiCapability.SUMMARIZATION -> copy(
                summarizationUseDefault = false, summarizationPreset = "openai",
                summarizationBaseUrl = "https://api.openai.com/v1", summarizationApiKey = "sk-summarization",
                summarizationModel = "gpt-4o", summarizationCustomHeaders = """{"X-Cap": "s"}""",
                summarizationTemperature = 0.5, summarizationMaxTokens = 3072
            )
            AiCapability.SPELL_CHECK -> copy(
                spellCheckUseDefault = false, spellCheckPreset = "openai",
                spellCheckBaseUrl = "https://api.openai.com/v1", spellCheckApiKey = "sk-spellcheck",
                spellCheckModel = "gpt-4o-mini", spellCheckCustomHeaders = """{"X-Cap": "c"}""",
                spellCheckTemperature = 0.0, spellCheckMaxTokens = 512
            )
            AiCapability.DICTIONARY -> copy(
                dictionaryUseDefault = false, dictionaryPreset = "openai",
                dictionaryBaseUrl = "https://api.openai.com/v1", dictionaryApiKey = "sk-dictionary",
                dictionaryModel = "gpt-4o", dictionaryCustomHeaders = """{"X-Cap": "d"}""",
                dictionaryTemperature = 0.2, dictionaryMaxTokens = 768
            )
            AiCapability.VISION -> copy(
                visionUseDefault = false, visionPreset = "openai",
                visionBaseUrl = "https://api.openai.com/v1", visionApiKey = "sk-vision",
                visionModel = "gpt-4o", visionCustomHeaders = """{"X-Cap": "v"}""",
                visionTemperature = 0.3, visionMaxTokens = 1500
            )
        }

        val base = AISettings().baseUrl
        val expectedModels = mapOf(
            AiCapability.TRANSLATION to "gpt-4o",
            AiCapability.REWRITE to "gpt-4o-mini",
            AiCapability.SUMMARIZATION to "gpt-4o",
            AiCapability.SPELL_CHECK to "gpt-4o-mini",
            AiCapability.DICTIONARY to "gpt-4o",
            AiCapability.VISION to "gpt-4o"
        )
        val expectedTemperatures = mapOf(
            AiCapability.TRANSLATION to 0.1,
            AiCapability.REWRITE to 0.9,
            AiCapability.SUMMARIZATION to 0.5,
            AiCapability.SPELL_CHECK to 0.0,
            AiCapability.DICTIONARY to 0.2,
            AiCapability.VISION to 0.3
        )
        val expectedMaxTokens = mapOf(
            AiCapability.TRANSLATION to 1024,
            AiCapability.REWRITE to 2048,
            AiCapability.SUMMARIZATION to 3072,
            AiCapability.SPELL_CHECK to 512,
            AiCapability.DICTIONARY to 768,
            AiCapability.VISION to 1500
        )
        val expectedHeaders = mapOf(
            AiCapability.TRANSLATION to """{"X-Cap": "t"}""",
            AiCapability.REWRITE to """{"X-Cap": "r"}""",
            AiCapability.SUMMARIZATION to """{"X-Cap": "s"}""",
            AiCapability.SPELL_CHECK to """{"X-Cap": "c"}""",
            AiCapability.DICTIONARY to """{"X-Cap": "d"}""",
            AiCapability.VISION to """{"X-Cap": "v"}"""
        )
        allCapabilities.forEach { capability ->
            val settings = AISettings().withOverrides(capability)
            val profile = settings.profileFor(capability)
            assertEquals("openai", profile.preset, "$capability preset")
            assertEquals("https://api.openai.com/v1", profile.baseUrl, "$capability baseUrl")
            assertEquals("sk-${capability.name.lowercase().replace("_", "")}", profile.apiKey, "$capability apiKey")
            assertEquals(expectedModels[capability], profile.model, "$capability model")
            assertEquals(expectedTemperatures[capability]!!, profile.temperature, "$capability temperature")
            assertEquals(expectedMaxTokens[capability]!!, profile.maxTokens, "$capability maxTokens")
            assertEquals(expectedHeaders[capability], profile.customHeaders, "$capability headers")
            // Every other capability still inherits the Default.
            allCapabilities.filter { it != capability }.forEach { other ->
                assertEquals(base, settings.profileFor(other).baseUrl, "$other should still inherit the Default baseUrl")
            }
        }
    }

    @Test
    fun `partial override of only the model inherits the rest from the Default`() {
        val settings = AISettings(
            apiKey = "sk-default"
        ).copy(translationUseDefault = false, translationModel = "gpt-4o")
        val d = settings.defaultProfile()
        val profile = settings.profileFor(AiCapability.TRANSLATION)
        assertEquals("gpt-4o", profile.model)
        assertEquals(d.preset, profile.preset)
        assertEquals(d.baseUrl, profile.baseUrl)
        assertEquals(d.apiKey, profile.apiKey)
        assertEquals(d.customHeaders, profile.customHeaders)
        assertEquals(d.temperature, profile.temperature)
        assertEquals(d.maxTokens, profile.maxTokens)
    }

    @Test
    fun `negative temperature and token sentinels inherit the Default`() {
        val settings = AISettings().copy(
            rewriteUseDefault = false,
            rewriteTemperature = -1.0,
            rewriteMaxTokens = -1
        )
        val d = settings.defaultProfile()
        val profile = settings.profileFor(AiCapability.REWRITE)
        assertEquals(d.temperature, profile.temperature)
        assertEquals(d.maxTokens, profile.maxTokens)
    }

    @Test
    fun `useDefault true ignores stored overrides even when non-blank`() {
        val settings = AISettings(
            apiKey = "sk-default"
        ).copy(
            summarizationUseDefault = true,
            summarizationPreset = "openai",
            summarizationBaseUrl = "https://api.openai.com/v1",
            summarizationApiKey = "sk-summarization",
            summarizationModel = "gpt-4o",
            summarizationCustomHeaders = """{"X-Cap": "s"}""",
            summarizationTemperature = 1.5,
            summarizationMaxTokens = 100
        )
        assertEquals(settings.defaultProfile(), settings.profileFor(AiCapability.SUMMARIZATION))
    }

    @Test
    fun `blank override key inherits Default key unless the resolved endpoint is local`() {
        // Hosted Default: blank override key inherits the Default key.
        val hosted = AISettings(apiKey = "sk-default").copy(
            dictionaryUseDefault = false, dictionaryModel = "gpt-4o-mini"
        )
        assertEquals("sk-default", hosted.profileFor(AiCapability.DICTIONARY).apiKey)

        // Independent local endpoint with a blank override key: no key at all, even
        // though the (hosted) Default has one.
        val local = AISettings(apiKey = "sk-default").copy(
            visionUseDefault = false,
            visionPreset = "local",
            visionBaseUrl = "http://localhost:11434/v1",
            visionModel = "llava"
        )
        val vision = local.profileFor(AiCapability.VISION)
        assertEquals("", vision.apiKey)
        assertTrue(vision.isLocalEndpoint)
        assertNull(vision.missingKeyError())
    }

    @Test
    fun `Default change propagates to inherited capabilities but not independent ones`() {
        val settings = AISettings(
            apiKey = "sk-default", model = "openrouter/free"
        ).copy(
            translationUseDefault = false, translationModel = "gpt-4o"
        )
        // Change the Default underneath both capabilities.
        val changed = settings.copy(baseUrl = "https://api.openai.com/v1", model = "gpt-4o-mini")
        assertEquals("https://api.openai.com/v1", changed.profileFor(AiCapability.REWRITE).baseUrl)
        assertEquals("gpt-4o-mini", changed.profileFor(AiCapability.REWRITE).model)
        // The independent Translation profile keeps its own model but inherits the new URL.
        assertEquals("gpt-4o", changed.profileFor(AiCapability.TRANSLATION).model)
        assertEquals("https://api.openai.com/v1", changed.profileFor(AiCapability.TRANSLATION).baseUrl)
    }

    @Test
    fun `apiKey survives data class copy`() {
        // SecretStore persistence is Agent C's concern; this pins that copying a profile
        // snapshot never drops the key field out from under it.
        val settings = AISettings(baseUrl = "https://openrouter.ai/api/v1", apiKey = "sk-test-123")
        assertEquals("sk-test-123", settings.copy().apiKey)
        assertEquals("sk-test-123", settings.copy(temperature = 1.0).apiKey)
    }

    @Test
    fun `settings delegation still matches the resolved Default profile`() {
        val hosted = AISettings(baseUrl = "https://openrouter.ai/api/v1", apiKey = "")
        assertFalse(hosted.isLocalEndpoint)
        assertNotNull(hosted.missingKeyError())
        assertEquals(hosted.defaultProfile().missingKeyError()?.message, hosted.missingKeyError()?.message)

        val local = AISettings(baseUrl = "http://localhost:11434/v1", apiKey = "")
        assertTrue(local.isLocalEndpoint)
        assertNull(local.missingKeyError())
    }
}
