package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcrTranslationDestinationTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    @Test
    fun `default destination is POPUP`() {
        assertEquals(OcrTranslationDestination.POPUP, Configuration.DEFAULT.ocrTranslationDestination)
    }

    @Test
    fun `round-trip serialization preserves POPUP destination`() {
        val config = Configuration.DEFAULT.copy(ocrTranslationDestination = OcrTranslationDestination.POPUP)
        val serialized = json.encodeToString(Configuration.serializer(), config)
        val deserialized = json.decodeFromString(Configuration.serializer(), serialized)

        assertEquals(OcrTranslationDestination.POPUP, deserialized.ocrTranslationDestination)
    }

    @Test
    fun `round-trip serialization preserves MAIN_WINDOW destination`() {
        val config = Configuration.DEFAULT.copy(ocrTranslationDestination = OcrTranslationDestination.MAIN_WINDOW)
        val serialized = json.encodeToString(Configuration.serializer(), config)
        val deserialized = json.decodeFromString(Configuration.serializer(), serialized)

        assertEquals(OcrTranslationDestination.MAIN_WINDOW, deserialized.ocrTranslationDestination)
    }

    @Test
    fun `deserializing older configuration without ocrTranslationDestination defaults to POPUP`() {
        val legacyJson = """
            {
                "configVersion": 7,
                "interfaceLanguage": "en"
            }
        """.trimIndent()

        val deserialized = json.decodeFromString(Configuration.serializer(), legacyJson)
        assertEquals(OcrTranslationDestination.POPUP, deserialized.ocrTranslationDestination)
    }
}
