package com.github.ahatem.qtranslate.plugins.speech

import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechSettingsMigrationTest {

    @Test
    fun `liveEnabled follows engine selection and mode`() {
        val offSettings = SpeechSettings(mode = MODE_HYBRID, liveEngine = LIVE_OFF)
        assertFalse(offSettings.liveEnabled)

        val builtinSettings = SpeechSettings(mode = MODE_HYBRID, liveEngine = LIVE_VOSK_BUILTIN)
        assertTrue(builtinSettings.liveEnabled, "Built-in engine is enabled")

        val cloudOnlySettings = SpeechSettings(mode = MODE_CLOUD, liveEngine = LIVE_VOSK_BUILTIN)
        assertFalse(cloudOnlySettings.liveEnabled, "Live text is disabled in Cloud only mode")

        val localSettings = SpeechSettings(mode = MODE_LOCAL, liveEngine = LIVE_VOSK_BUILTIN)
        assertTrue(localSettings.liveEnabled, "Live text is enabled in Local only mode")

        val localDeepgram = SpeechSettings(mode = MODE_LOCAL, liveEngine = LIVE_DEEPGRAM, liveApiKey = "key123")
        assertFalse(localDeepgram.liveEnabled, "Deepgram live must NOT be enabled in Local only mode")

        val deepgramSettings = SpeechSettings(mode = MODE_HYBRID, liveEngine = LIVE_DEEPGRAM, liveApiKey = "key123")
        assertTrue(deepgramSettings.liveEnabled, "Deepgram live is enabled with key")
    }

    @Test
    fun `migration rules map legacy combinations to mode`() {
        // Vosk + provider -> Hybrid
        assertEquals(MODE_HYBRID, SpeechPlugin.migrateSpeechMode(null, "Vosk (local server)", "Groq"))
        assertEquals(MODE_HYBRID, SpeechPlugin.migrateSpeechMode(null, LIVE_VOSK_BUILTIN, "OpenAI"))

        // Vosk + None -> Local
        assertEquals(MODE_LOCAL, SpeechPlugin.migrateSpeechMode(null, "Vosk (local server)", SpeechProvider.NONE.label))
        assertEquals(MODE_LOCAL, SpeechPlugin.migrateSpeechMode(null, LIVE_VOSK_BUILTIN, SpeechProvider.NONE.label))

        // Off -> Cloud
        assertEquals(MODE_CLOUD, SpeechPlugin.migrateSpeechMode(null, LIVE_OFF, "Groq"))

        // Saved mode is preserved
        assertEquals(MODE_LOCAL, SpeechPlugin.migrateSpeechMode(MODE_LOCAL, LIVE_VOSK_BUILTIN, "Groq"))
        assertEquals(MODE_CLOUD, SpeechPlugin.migrateSpeechMode(MODE_CLOUD, LIVE_VOSK_BUILTIN, "Groq"))
        assertEquals(MODE_HYBRID, SpeechPlugin.migrateSpeechMode(MODE_HYBRID, LIVE_OFF, SpeechProvider.NONE.label))
    }

    @Test
    fun `Local only mode treats speech provider as None without URL errors`() {
        val settings = SpeechSettings(
            mode = MODE_LOCAL,
            provider = "Groq",
            apiKey = "",
            liveEngine = LIVE_VOSK_BUILTIN
        )
        assertEquals(SpeechProvider.NONE, settings.speechProvider)
        assertNull(settings.urlError(), "Local only mode requires no cloud API key or provider")
        assertTrue(settings.configurationError() is com.github.ahatem.qtranslate.api.plugin.ServiceError.ConfigurationError)
    }

    @Test
    fun `Cloud only mode requires provider configuration`() {
        val unconfigured = SpeechSettings(
            mode = MODE_CLOUD,
            provider = "Groq",
            apiKey = ""
        )
        assertEquals(SpeechProvider.GROQ, unconfigured.speechProvider)
        assertTrue(unconfigured.configurationError() != null, "Missing API key triggers configuration error")

        val configured = SpeechSettings(
            mode = MODE_CLOUD,
            provider = "Groq",
            apiKey = "gsk_test123"
        )
        assertNull(configured.configurationError())
    }

    @Test
    fun `settings schema has mode and proper showIf rules and removes liveServerUrl and liveAutoLanguage`() {
        val fields = SpeechSettings::class.java.declaredFields.associateBy { it.name }
        assertFalse(fields.containsKey("liveAutoLanguage"), "liveAutoLanguage field must be removed")
        assertFalse(fields.containsKey("liveServerUrl"), "liveServerUrl field must be removed")
        assertTrue(fields.containsKey("mode"), "mode field must exist")

        val providerAnn = fields["provider"]?.getAnnotation(com.github.ahatem.qtranslate.api.settings.Setting::class.java)
        assertEquals("mode=!Local only", providerAnn?.showIf)

        val liveEngineAnn = fields["liveEngine"]?.getAnnotation(com.github.ahatem.qtranslate.api.settings.Setting::class.java)
        assertEquals("mode=!Cloud only", liveEngineAnn?.showIf)
    }

    @Test
    fun `VoskLiveService rejects cloud live engines in Local only mode`() = kotlinx.coroutines.runBlocking {
        val settings = SpeechSettings(mode = MODE_LOCAL, liveEngine = LIVE_DEEPGRAM, liveApiKey = "key123")
        val service = VoskLiveService({ settings })
        assertTrue(service.openSession(com.github.ahatem.qtranslate.api.language.LanguageCode("en")).getError() is com.github.ahatem.qtranslate.api.plugin.ServiceError.ConfigurationError)
        assertTrue(service.validate().getError() is com.github.ahatem.qtranslate.api.plugin.ServiceError.ConfigurationError)
    }
}
