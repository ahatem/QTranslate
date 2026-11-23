package com.github.ahatem.qtranslate.plugins.ai

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.ImageData
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.plugins.common.CapturingLogger
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.common.ScriptedHttpClient
import com.github.ahatem.qtranslate.plugins.common.unwrap
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Lifecycle / persistence integration for [AIPlugin]: Default + per-capability
 * overrides round-trip through settings/secrets, profiles stay independent,
 * distinct-profile validation deduplicates, and failures never mutate stored
 * state or leak keys. All HTTP is scripted; all keys are synthetic.
 */
class AIPluginTest {

    private companion object {
        const val COMPLETION = """{"choices":[{"message":{"role":"assistant","content":"1"}}]}"""

        fun completionWith(text: String) =
            """{"choices":[{"message":{"role":"assistant","content":"$text"}}]}"""

        val OCR_COMPLETION = """{"choices":[{"message":{"role":"assistant","content":"{\"text\":\"Hello OCR\",\"detected_language\":\"en\"}"}}]}"""
    }

    private suspend fun initializedPlugin(
        context: FakePluginContext
    ): AIPlugin {
        val plugin = AIPlugin()
        plugin.initialize(context).unwrap()
        plugin.onEnable().unwrap()
        return plugin
    }

    private fun failureOf(result: Result<Unit, ServiceError>): ServiceError =
        result.fold(
            success = { fail("expected a failure, got success") },
            failure = { it }
        )

    // (a) round-trip default fields -------------------------------------------------

    @Test
    fun `default fields round-trip through settings and survive reinitialize`() = runBlocking {
        val http = ScriptedHttpClient(listOf())
        val context = FakePluginContext(httpClient = http)
        val plugin = initializedPlugin(context)

        val saved = AISettings(
            preset = "openai",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "synthetic-default-key-001",
            model = "gpt-4o-mini",
            temperature = 0.7,
            maxTokens = 1234,
            customHeaders = """{"X-Test":"yes"}"""
        )
        plugin.onSettingsChanged(saved).unwrap()

        assertEquals("openai", context.settings.getString("preset"))
        assertEquals("https://api.openai.com/v1", context.settings.getString("baseUrl"))
        assertEquals("gpt-4o-mini", context.settings.getString("model"))
        assertEquals(0.7, context.settings.getDouble("temperature", 0.0))
        assertEquals(1234, context.settings.getInt("maxTokens", 0))
        assertEquals("""{"X-Test":"yes"}""", context.settings.getString("customHeaders"))
        assertEquals("synthetic-default-key-001", context.secrets.get("apiKey"))

        // A fresh plugin reading the same stores sees the same Default profile.
        val reloaded = AIPlugin()
        reloaded.initialize(context)
        val loaded = reloaded.getSettings()
        assertEquals("openai", loaded.preset)
        assertEquals("https://api.openai.com/v1", loaded.baseUrl)
        assertEquals("gpt-4o-mini", loaded.model)
        assertEquals(0.7, loaded.temperature)
        assertEquals(1234, loaded.maxTokens)
        assertEquals("""{"X-Test":"yes"}""", loaded.customHeaders)
        assertEquals("synthetic-default-key-001", loaded.apiKey)
    }

    // (b) override apiKeys land in secrets, not settings ----------------------------

    @Test
    fun `override apiKeys land in secrets and never in settings`() = runBlocking {
        val http = ScriptedHttpClient(listOf())
        val context = FakePluginContext(httpClient = http)
        val plugin = initializedPlugin(context)

        val saved = AISettings(
            apiKey = "synthetic-default-key-002",
            translationUseDefault = false,
            translationApiKey = "synthetic-translation-key-002",
            translationModel = "translation-model",
            rewriteUseDefault = false,
            rewriteApiKey = "synthetic-rewrite-key-002",
            summarizationUseDefault = false,
            summarizationApiKey = "synthetic-summarization-key-002",
            spellCheckUseDefault = false,
            spellCheckApiKey = "synthetic-spellcheck-key-002",
            dictionaryUseDefault = false,
            dictionaryApiKey = "synthetic-dictionary-key-002",
            visionUseDefault = false,
            visionApiKey = "synthetic-vision-key-002"
        )
        plugin.onSettingsChanged(saved).unwrap()

        // Secrets hold every key under its own key.
        assertEquals("synthetic-default-key-002", context.secrets.get("apiKey"))
        assertEquals("synthetic-translation-key-002", context.secrets.get("translationApiKey"))
        assertEquals("synthetic-rewrite-key-002", context.secrets.get("rewriteApiKey"))
        assertEquals("synthetic-summarization-key-002", context.secrets.get("summarizationApiKey"))
        assertEquals("synthetic-spellcheck-key-002", context.secrets.get("spellCheckApiKey"))
        assertEquals("synthetic-dictionary-key-002", context.secrets.get("dictionaryApiKey"))
        assertEquals("synthetic-vision-key-002", context.secrets.get("visionApiKey"))
        assertTrue(context.secrets.contains("translationApiKey"))
        assertTrue(context.secrets.contains("visionApiKey"))

        // Settings hold no apiKey at all.
        assertNull(context.settings.getString("apiKey"), "apiKey must not be in settings")
        assertNull(context.settings.getString("translationApiKey"))
        assertNull(context.settings.getString("rewriteApiKey"))
        assertNull(context.settings.getString("summarizationApiKey"))
        assertNull(context.settings.getString("spellCheckApiKey"))
        assertNull(context.settings.getString("dictionaryApiKey"))
        assertNull(context.settings.getString("visionApiKey"))

        // Override non-secret fields do round-trip through settings.
        assertEquals(false, context.settings.getBoolean("translationUseDefault", true))
        assertEquals("translation-model", context.settings.getString("translationModel"))
    }

    // (c) independence --------------------------------------------------------------

    @Test
    fun `inherited translator follows default while vision override is independent`() = runBlocking {
        val http = ScriptedHttpClient(
            listOf(
                Ok(completionWith("Bonjour")),
                Ok(OCR_COMPLETION)
            )
        )
        val context = FakePluginContext(httpClient = http)
        val plugin = initializedPlugin(context)

        val saved = AISettings(
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "synthetic-default-key-003",
            model = "default-model",
            visionUseDefault = false,
            visionBaseUrl = "https://vision.example.invalid/v1",
            visionApiKey = "synthetic-vision-key-003",
            visionModel = "vision-model"
        )
        plugin.onSettingsChanged(saved).unwrap()
        plugin.onEnable()

        val translator = plugin.getServices().first { it.key == "ai-translator" } as Translator
        val vision = plugin.getServices().first { it.key == "ai-ocr" } as OCR

        translator.translate(
            TranslationRequest("Hello", LanguageCode.ENGLISH, LanguageCode.FRENCH)
        ).unwrap().also { assertEquals("Bonjour", it.translatedText) }
        vision.extractText(
            OCRRequest(ImageData(byteArrayOf(1, 2, 3), "png", 1, 1), LanguageCode.ENGLISH)
        ).unwrap().also { assertEquals("Hello OCR", it.text) }

        // Translator: Default endpoint + Default model + Default key.
        assertEquals("https://openrouter.ai/api/v1/chat/completions", http.urls[0])
        assertTrue(http.bodies[0].contains("\"model\":\"default-model\""), "body was: ${http.bodies[0]}")
        assertEquals("Bearer synthetic-default-key-003", http.headers[0]["Authorization"])

        // Vision: its own endpoint + model + key.
        assertEquals("https://vision.example.invalid/v1/chat/completions", http.urls[1])
        assertTrue(http.bodies[1].contains("\"model\":\"vision-model\""), "body was: ${http.bodies[1]}")
        assertEquals("Bearer synthetic-vision-key-003", http.headers[1]["Authorization"])
    }

    // (d) dedup ---------------------------------------------------------------------

    @Test
    fun `all-inherit validateAllDistinctProfiles makes one POST`() = runBlocking {
        val http = ScriptedHttpClient(listOf(Ok(COMPLETION), Ok(COMPLETION)))
        val context = FakePluginContext(httpClient = http)
        val plugin = initializedPlugin(context)
        plugin.onSettingsChanged(
            AISettings(apiKey = "synthetic-default-key-004")
        ).unwrap()

        val client = AIServiceClient(pluginContext = context, httpClient = http)
        plugin.validateAllDistinctProfiles(client).unwrap()
        assertEquals(1, http.urls.size)
    }

    @Test
    fun `vision-different validateAllDistinctProfiles makes two POSTs`() = runBlocking {
        val http = ScriptedHttpClient(listOf(Ok(COMPLETION), Ok(COMPLETION), Ok(COMPLETION)))
        val context = FakePluginContext(httpClient = http)
        val plugin = initializedPlugin(context)
        plugin.onSettingsChanged(
            AISettings(
                apiKey = "synthetic-default-key-005",
                visionUseDefault = false,
                visionBaseUrl = "https://vision.example.invalid/v1",
                visionApiKey = "synthetic-vision-key-005",
                visionModel = "vision-model"
            )
        ).unwrap()

        val client = AIServiceClient(pluginContext = context, httpClient = http)
        plugin.validateAllDistinctProfiles(client).unwrap()
        assertEquals(2, http.urls.size)
        assertTrue(http.bodies.any { it.contains("\"model\":\"vision-model\"") }, "bodies were: ${http.bodies}")
        assertTrue(http.urls.any { it.contains("vision.example.invalid") }, "urls were: ${http.urls}")
    }

    // (e) failed onSettingsChanged leaves stored settings untouched -----------------

    @Test
    fun `failed onSettingsChanged does not mutate previous stored settings`() = runBlocking {
        val http = ScriptedHttpClient(listOf())
        val context = FakePluginContext(httpClient = http)
        val plugin = initializedPlugin(context)

        val good = AISettings(
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "synthetic-good-key-006",
            model = "good-model"
        )
        plugin.onSettingsChanged(good).unwrap()

        val bad = good.copy(baseUrl = "", model = "", apiKey = "synthetic-bad-key-006", temperature = 9.9)
        val failure = failureOf(plugin.onSettingsChanged(bad))
        assertIs<ServiceError.ValidationError>(failure)

        // Stored settings, secrets and in-memory settings all keep the good values.
        assertEquals("https://openrouter.ai/api/v1", context.settings.getString("baseUrl"))
        assertEquals("good-model", context.settings.getString("model"))
        assertEquals("synthetic-good-key-006", context.secrets.get("apiKey"))
        assertEquals("good-model", plugin.getSettings().model)
        assertEquals("synthetic-good-key-006", plugin.getSettings().apiKey)
    }

    // (f) failed connection test leaves secrets intact ------------------------------

    // (g) error messages contain no synthetic key -----------------------------------

    @Test
    fun `error messages and logs contain no synthetic key`() = runBlocking {
        val logger = CapturingLogger()
        val syntheticKey = "SYNTHETIC-KEY-008-leak-check"
        val http = ScriptedHttpClient(
            listOf(Err(ServiceError.UnknownError("connection refused")))
        )
        val context = FakePluginContext(logger = logger, httpClient = http)
        val plugin = initializedPlugin(context)

        // Validation failure.
        val validationFailure = failureOf(
            plugin.onSettingsChanged(AISettings(apiKey = syntheticKey, model = ""))
        )
        assertFalse(validationFailure.message.contains(syntheticKey), "validation error leaked the key")

        // Persist a good profile, then a connection failure.
        plugin.onSettingsChanged(AISettings(apiKey = syntheticKey)).unwrap()
        val client = AIServiceClient(pluginContext = context, httpClient = http)
        val connectionFailure = failureOf(plugin.validateAllDistinctProfiles(client))
        assertFalse(connectionFailure.message.contains(syntheticKey), "connection error leaked the key")

        val captured = logger.messages + logger.loggedThrowables
        assertTrue(
            captured.none { it.contains(syntheticKey) },
            "synthetic key leaked into logs: $captured"
        )
    }
}
