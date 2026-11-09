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
import com.github.ahatem.qtranslate.plugins.common.TextHttpClient
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

    private class ScriptedHttpClient(
        private val responses: MutableList<Result<String, ServiceError>>
    ) : TextHttpClient() {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        val bodies = mutableListOf<String?>()

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String?,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> {
            urls += url
            this.headers += headers
            bodies += body
            return responses.removeFirstOrNull()
                ?: error("No scripted response left for POST $url")
        }

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            queryParams: Map<String, Any?>
        ): Result<String, ServiceError> =
            error("The AI client should not issue GETs, but one went to $url")
    }

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
        plugin.initialize(context).fold(
            success = { },
            failure = { fail("initialize should pass: ${it.message}") }
        )
        plugin.onEnable().fold(
            success = { },
            failure = { fail("onEnable should pass: ${it.message}") }
        )
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
        val http = ScriptedHttpClient(mutableListOf())
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
        plugin.onSettingsChanged(saved).fold(
            success = { },
            failure = { fail("onSettingsChanged should pass: ${it.message}") }
        )

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
        val http = ScriptedHttpClient(mutableListOf())
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
        plugin.onSettingsChanged(saved).fold(
            success = { },
            failure = { fail("onSettingsChanged should pass: ${it.message}") }
        )

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
            mutableListOf(
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
        plugin.onSettingsChanged(saved).fold(
            success = { },
            failure = { fail("onSettingsChanged should pass: ${it.message}") }
        )
        plugin.onEnable()

        val translator = plugin.getServices().first { it.key == "ai-translator" } as Translator
        val vision = plugin.getServices().first { it.key == "ai-ocr" } as OCR

        translator.translate(
            TranslationRequest("Hello", LanguageCode.ENGLISH, LanguageCode.FRENCH)
        ).fold(
            success = { assertEquals("Bonjour", it.translatedText) },
            failure = { fail("translator should pass: ${it.message}") }
        )
        vision.extractText(
            OCRRequest(ImageData(byteArrayOf(1, 2, 3), "png", 1, 1), LanguageCode.ENGLISH)
        ).fold(
            success = { assertEquals("Hello OCR", it.text) },
            failure = { fail("vision should pass: ${it.message}") }
        )

        // Translator: Default endpoint + Default model + Default key.
        assertEquals("https://openrouter.ai/api/v1/chat/completions", http.urls[0])
        assertTrue(http.bodies[0].orEmpty().contains("\"model\":\"default-model\""), "body was: ${http.bodies[0]}")
        assertEquals("Bearer synthetic-default-key-003", http.headers[0]["Authorization"])

        // Vision: its own endpoint + model + key.
        assertEquals("https://vision.example.invalid/v1/chat/completions", http.urls[1])
        assertTrue(http.bodies[1].orEmpty().contains("\"model\":\"vision-model\""), "body was: ${http.bodies[1]}")
        assertEquals("Bearer synthetic-vision-key-003", http.headers[1]["Authorization"])
    }

    // (d) dedup ---------------------------------------------------------------------

    @Test
    fun `all-inherit validateAllDistinctProfiles makes one POST`() = runBlocking {
        val http = ScriptedHttpClient(mutableListOf(Ok(COMPLETION), Ok(COMPLETION)))
        val context = FakePluginContext(httpClient = http)
        val plugin = initializedPlugin(context)
        plugin.onSettingsChanged(
            AISettings(apiKey = "synthetic-default-key-004")
        ).fold(
            success = { },
            failure = { fail("onSettingsChanged should pass: ${it.message}") }
        )

        val client = AIServiceClient(pluginContext = context, httpClient = http)
        plugin.validateAllDistinctProfiles(client).fold(
            success = { },
            failure = { fail("validateAllDistinctProfiles should pass: ${it.message}") }
        )
        assertEquals(1, http.urls.size)
    }

    @Test
    fun `vision-different validateAllDistinctProfiles makes two POSTs`() = runBlocking {
        val http = ScriptedHttpClient(mutableListOf(Ok(COMPLETION), Ok(COMPLETION), Ok(COMPLETION)))
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
        ).fold(
            success = { },
            failure = { fail("onSettingsChanged should pass: ${it.message}") }
        )

        val client = AIServiceClient(pluginContext = context, httpClient = http)
        plugin.validateAllDistinctProfiles(client).fold(
            success = { },
            failure = { fail("validateAllDistinctProfiles should pass: ${it.message}") }
        )
        assertEquals(2, http.urls.size)
        assertTrue(http.bodies.any { it.orEmpty().contains("\"model\":\"vision-model\"") }, "bodies were: ${http.bodies}")
        assertTrue(http.urls.any { it.contains("vision.example.invalid") }, "urls were: ${http.urls}")
    }

    // (e) failed onSettingsChanged leaves stored settings untouched -----------------

    @Test
    fun `failed onSettingsChanged does not mutate previous stored settings`() = runBlocking {
        val http = ScriptedHttpClient(mutableListOf())
        val context = FakePluginContext(httpClient = http)
        val plugin = initializedPlugin(context)

        val good = AISettings(
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "synthetic-good-key-006",
            model = "good-model"
        )
        plugin.onSettingsChanged(good).fold(
            success = { },
            failure = { fail("good settings should pass: ${it.message}") }
        )

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

    @Test
    fun `failed connection test leaves secrets intact`() = runBlocking {
        val http = ScriptedHttpClient(
            mutableListOf(Err(ServiceError.UnknownError("boom")))
        )
        val context = FakePluginContext(httpClient = http)
        val plugin = initializedPlugin(context)
        plugin.onSettingsChanged(
            AISettings(
                apiKey = "synthetic-default-key-007",
                visionUseDefault = false,
                visionApiKey = "synthetic-vision-key-007",
                visionModel = "vision-model"
            )
        ).fold(
            success = { },
            failure = { fail("onSettingsChanged should pass: ${it.message}") }
        )

        val client = AIServiceClient(pluginContext = context, httpClient = http)
        val failure = failureOf(plugin.validateAllDistinctProfiles(client))
        assertEquals("boom", failure.message)

        assertEquals("synthetic-default-key-007", context.secrets.get("apiKey"))
        assertEquals("synthetic-vision-key-007", context.secrets.get("visionApiKey"))
        assertTrue(context.secrets.contains("apiKey"))
        assertTrue(context.secrets.contains("visionApiKey"))
    }

    // (g) error messages contain no synthetic key -----------------------------------

    @Test
    fun `error messages and logs contain no synthetic key`() = runBlocking {
        val logger = CapturingLogger()
        val syntheticKey = "SYNTHETIC-KEY-008-leak-check"
        val http = ScriptedHttpClient(
            mutableListOf(Err(ServiceError.UnknownError("connection refused")))
        )
        val context = FakePluginContext(logger = logger, httpClient = http)
        val plugin = initializedPlugin(context)

        // Validation failure.
        val validationFailure = failureOf(
            plugin.onSettingsChanged(AISettings(apiKey = syntheticKey, model = ""))
        )
        assertFalse(validationFailure.message.contains(syntheticKey), "validation error leaked the key")

        // Persist a good profile, then a connection failure.
        plugin.onSettingsChanged(AISettings(apiKey = syntheticKey)).fold(
            success = { },
            failure = { fail("good settings should pass: ${it.message}") }
        )
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
