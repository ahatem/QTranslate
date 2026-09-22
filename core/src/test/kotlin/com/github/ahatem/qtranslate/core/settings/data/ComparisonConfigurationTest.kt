package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.michaelbull.result.Ok
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class ComparisonConfigurationTest {

    @Test
    fun `old preset config loads without comparisons`() {
        val json = Json { ignoreUnknownKeys = true }
        val preset = json.decodeFromString<ServicePreset>(
            """{"id":"p","name":"Default","selectedServices":{"TRANSLATOR":"primary"}}"""
        )

        assertEquals(emptyList(), preset.comparisonTranslatorIds)
    }

    @Test
    fun `comparison ids preserve order and round trip`() {
        val json = Json { encodeDefaults = true }
        val preset = ServicePreset(
            id = "p",
            name = "Default",
            selectedServices = mapOf(ServiceRole.TRANSLATOR to "primary"),
            comparisonTranslatorIds = listOf("second", "first")
        )

        val decoded = json.decodeFromString<ServicePreset>(json.encodeToString(preset))
        assertEquals(listOf("second", "first"), decoded.comparisonTranslatorIds)
    }

    @Test
    fun `exact resolver does not fall back`() {
        val primary = TestTranslator("primary")
        val other = TestTranslator("other")
        val preset = Configuration.DEFAULT.getActivePreset()!!.copy(
            selectedServices = mapOf(ServiceRole.TRANSLATOR to "primary")
        )
        val config = Configuration.DEFAULT.copy(
            servicePresets = listOf(preset),
            activeServicePresetId = preset.id
        )
        val manager = ActiveServiceManager(
            kotlinx.coroutines.flow.MutableStateFlow(mapOf("primary" to primary, "other" to other)),
            kotlinx.coroutines.flow.MutableStateFlow(config)
        )

        assertEquals("other", manager.resolve<Translator>("other", ServiceRole.TRANSLATOR)?.id)
        assertEquals(null, manager.resolve<Translator>("missing", ServiceRole.TRANSLATOR))
        assertEquals(null, manager.resolve<Translator>("other", ServiceRole.DICTIONARY))
    }

    @Test
    fun `exact resolver rejects disabled service`() {
        val service = TestTranslator("disabled")
        val preset = Configuration.DEFAULT.getActivePreset()!!.copy(
            selectedServices = mapOf(ServiceRole.TRANSLATOR to "disabled")
        )
        val config = Configuration.DEFAULT.copy(
            servicePresets = listOf(preset),
            activeServicePresetId = preset.id,
            disabledServices = setOf("disabled")
        )
        val manager = ActiveServiceManager(
            kotlinx.coroutines.flow.MutableStateFlow(mapOf("disabled" to service)),
            kotlinx.coroutines.flow.MutableStateFlow(config)
        )

        assertEquals(null, manager.resolve<Translator>("disabled", ServiceRole.TRANSLATOR))
    }

    private class TestTranslator(override val key: String) : Translator {
        override val name = key
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.All

        override suspend fun translate(request: TranslationRequest) = Ok(TranslationResponse(key))
    }
}
