package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcrRemoveLineBreaksConfigTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    @Test
    fun `default setting is disabled`() {
        assertFalse(Configuration.DEFAULT.isRemoveOcrLineBreaksEnabled)
    }

    @Test
    fun `round-trip serialization preserves enabled setting`() {
        val config = Configuration.DEFAULT.copy(isRemoveOcrLineBreaksEnabled = true)
        val serialized = json.encodeToString(Configuration.serializer(), config)
        val deserialized = json.decodeFromString(Configuration.serializer(), serialized)

        assertTrue(deserialized.isRemoveOcrLineBreaksEnabled)
    }

    @Test
    fun `round-trip serialization preserves disabled setting`() {
        val config = Configuration.DEFAULT.copy(isRemoveOcrLineBreaksEnabled = false)
        val serialized = json.encodeToString(Configuration.serializer(), config)
        val deserialized = json.decodeFromString(Configuration.serializer(), serialized)

        assertFalse(deserialized.isRemoveOcrLineBreaksEnabled)
    }

    @Test
    fun `deserializing legacy configuration without isRemoveOcrLineBreaksEnabled defaults to false`() {
        val legacyJson = """
            {
                "configVersion": 7,
                "interfaceLanguage": "en"
            }
        """.trimIndent()

        val deserialized = json.decodeFromString(Configuration.serializer(), legacyJson)
        assertFalse(deserialized.isRemoveOcrLineBreaksEnabled)
    }
}
