package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class OcrTranslationDestinationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `default destination preserves the existing main-window behavior`() {
        assertEquals(OcrTranslationDestination.MAIN_WINDOW, Configuration.DEFAULT.ocrTranslationDestination)
    }

    @Test
    fun `every destination round-trips through configuration serialization`() {
        for (destination in OcrTranslationDestination.entries) {
            val config = Configuration.DEFAULT.copy(ocrTranslationDestination = destination)
            val serialized = json.encodeToString(Configuration.serializer(), config)
            val deserialized = json.decodeFromString(Configuration.serializer(), serialized)

            assertEquals(destination, deserialized.ocrTranslationDestination)
        }
    }

    @Test
    fun `deserializing older configuration without ocrTranslationDestination keeps the main window`() {
        val legacyJson = """
            {
                "configVersion": 7,
                "interfaceLanguage": "en"
            }
        """.trimIndent()

        val deserialized = json.decodeFromString(Configuration.serializer(), legacyJson)
        assertEquals(OcrTranslationDestination.MAIN_WINDOW, deserialized.ocrTranslationDestination)
    }
}
