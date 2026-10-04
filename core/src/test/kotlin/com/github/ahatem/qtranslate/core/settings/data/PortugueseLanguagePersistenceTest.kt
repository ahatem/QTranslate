package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PortugueseLanguagePersistenceTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun roundTrip(source: String, target: String): Configuration {
        val original = Configuration.DEFAULT.copy(
            preferredSourceLanguage = source,
            preferredTargetLanguage = target
        )
        return json.decodeFromString(json.encodeToString(original))
    }

    @Test
    fun `legacy generic pt settings load and round-trip unchanged`() {
        val restored = roundTrip("pt", "pt")
        assertEquals("pt", restored.preferredSourceLanguage)
        assertEquals("pt", restored.preferredTargetLanguage)
    }

    @Test
    fun `Brazilian Portuguese settings round-trip without normalization`() {
        val restored = roundTrip("pt-BR", "pt-BR")
        assertEquals("pt-BR", restored.preferredSourceLanguage)
        assertEquals("pt-BR", restored.preferredTargetLanguage)
    }

    @Test
    fun `European Portuguese settings round-trip without normalization`() {
        val restored = roundTrip("pt-PT", "pt-PT")
        assertEquals("pt-PT", restored.preferredSourceLanguage)
        assertEquals("pt-PT", restored.preferredTargetLanguage)
    }
}
