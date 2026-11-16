package com.github.ahatem.qtranslate.plugins.speech

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SpeechSettingsTest {

    @Test
    fun `private 172 16-31 hosts count as local and need no api key`() {
        fun error(url: String) = SpeechSettings(provider = "OpenAI", apiKey = "", baseUrl = url).configurationError()

        assertNull(error("http://172.16.0.5:8000/v1"))
        assertNull(error("http://172.31.255.1:8000/v1"))
        assertNotNull(error("http://172.32.0.1:8000/v1"))
        assertNotNull(error("http://172.15.0.1:8000/v1"))

        // IPv6 loopback and ULA (Unique Local Address fc00::/7)
        assertNull(error("http://[::1]:8000/v1"))
        assertNull(error("http://[fd12:3456:789a::1]:8000/v1"))
    }

    @Test
    fun `voskBuiltinModelDisplayLabel formats label with installed marker`() {
        val isolatedDir = java.nio.file.Files.createTempDirectory("speech-settings-test").toFile()
        val manager = VoskModelManager(dataDirectory = { isolatedDir }, persistentDirectoryProvider = { isolatedDir })
        val settings = SpeechSettings().apply { modelManager = manager }

        val labelUninstalled = settings.voskBuiltinModelDisplayLabel("vosk-model-small-en-us-0.15")
        kotlin.test.assertEquals("English (US) small ~40MB", labelUninstalled)

        // Install model fake files
        val modelDir = java.io.File(manager.modelsDirectory, "vosk-model-small-en-us-0.15").apply { mkdirs() }
        java.io.File(modelDir, "am").mkdirs()
        java.io.File(modelDir, "am/final.mdl").writeText("fake")
        java.io.File(modelDir, "conf").mkdirs()
        java.io.File(modelDir, "conf/model.conf").writeText("fake")
        java.io.File(modelDir, "graph").mkdirs()
        java.io.File(modelDir, "graph/HCLG.fst").writeText("fake")

        val labelInstalled = settings.voskBuiltinModelDisplayLabel("vosk-model-small-en-us-0.15")
        kotlin.test.assertEquals("English (US) small ~40MB ✓ (installed)", labelInstalled)
    }

    @Test
    fun `effectiveLiveApiKey only falls back to accurate apiKey when vendors match`() {
        // OpenAI accurate + OpenAI Realtime live -> matches vendor -> fallback permitted
        val openAiSettings = SpeechSettings(
            provider = "OpenAI",
            apiKey = "openai-key",
            liveEngine = LIVE_OPENAI,
            liveApiKey = ""
        )
        kotlin.test.assertEquals("openai-key", openAiSettings.effectiveLiveApiKey)

        // Groq accurate + Deepgram live -> vendors differ -> NO fallback
        val mixedSettings = SpeechSettings(
            provider = "Groq",
            apiKey = "groq-key",
            liveEngine = LIVE_DEEPGRAM,
            liveApiKey = ""
        )
        kotlin.test.assertEquals("", mixedSettings.effectiveLiveApiKey)

        // Deepgram accurate + Deepgram live -> matches vendor -> fallback permitted
        val deepgramSettings = SpeechSettings(
            provider = "Deepgram",
            apiKey = "dg-key",
            liveEngine = LIVE_DEEPGRAM,
            liveApiKey = ""
        )
        kotlin.test.assertEquals("dg-key", deepgramSettings.effectiveLiveApiKey)

        // Explicit liveApiKey always takes precedence regardless of vendor
        val explicitSettings = SpeechSettings(
            provider = "Groq",
            apiKey = "groq-key",
            liveEngine = LIVE_DEEPGRAM,
            liveApiKey = "explicit-dg-key"
        )
        kotlin.test.assertEquals("explicit-dg-key", explicitSettings.effectiveLiveApiKey)
    }

    @Test
    fun `E2 - DROPDOWN_OPTIONS matches AVAILABLE_MODELS names in order without gaps`() {
        val expected = VoskModelManager.AVAILABLE_MODELS.joinToString(",") { it.name }
        kotlin.test.assertEquals(expected, VoskModelManager.DROPDOWN_OPTIONS)
    }

    @Test
    fun `B7 - all localization keys exist in both en and ar bundles`() {
        val enStream = javaClass.getResourceAsStream("/localization/en.toml")
        val arStream = javaClass.getResourceAsStream("/localization/ar.toml")
        kotlin.test.assertNotNull(enStream, "en.toml should be present")
        kotlin.test.assertNotNull(arStream, "ar.toml should be present")

        fun parseKeys(input: java.io.InputStream): Set<String> {
            return input.bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("[") && it.contains("=") }
                    .map { it.substringBefore("=").trim() }
                    .toSet()
            }
        }

        val enKeys = parseKeys(enStream)
        val arKeys = parseKeys(arStream)

        kotlin.test.assertEquals(enKeys, arKeys, "en.toml and ar.toml key sets must be identical")
    }

    @Test
    fun `R7 - SpeechProvider DROPDOWN_OPTIONS matches enum entries in order`() {
        val expected = SpeechProvider.entries.joinToString(",") { it.label }
        kotlin.test.assertEquals(expected, SpeechProvider.DROPDOWN_OPTIONS)
    }

    @Test
    fun `R7 - LIVE_ENGINE_OPTIONS matches expected options in order`() {
        val expected = listOf(LIVE_OFF, LIVE_VOSK_BUILTIN, LIVE_DEEPGRAM, LIVE_OPENAI).joinToString(",")
        kotlin.test.assertEquals(expected, LIVE_ENGINE_OPTIONS)
    }

    @Test
    fun `P10 - unknown saved provider label maps to NONE to prevent cloud data leakage`() {
        val unknown = SpeechProvider.fromLabel("RetiredOrUnknownProvider")
        kotlin.test.assertEquals(SpeechProvider.NONE, unknown)
    }
}
