package com.github.ahatem.qtranslate.core.settings.data

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.plugin.registry.ServiceId
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class ServicePreset(
    val id: String,
    val name: String,
    val selectedServices: Map<ServiceRole, String?>,
    val comparisonTranslatorIds: List<String> = emptyList()
) {
    companion object {

        const val DEFAULT_PRESET_NAME = "__default__" // internal sentinel, never shown to user

        // Composed ids, matching what the registry keys these services under. A fresh install must
        // be valid on its own rather than depend on a migration to become so.
        private const val GOOGLE = "google-services"
        private val DEFAULT_TRANSLATOR    = ServiceId.of(GOOGLE, ServiceId.DEFAULT_INSTANCE, "google-translator")
        private val DEFAULT_TTS           = ServiceId.of(GOOGLE, ServiceId.DEFAULT_INSTANCE, "google-tts")
        private val DEFAULT_SPELL_CHECKER = ServiceId.of(GOOGLE, ServiceId.DEFAULT_INSTANCE, "google-spell-checker")
        private val DEFAULT_OCR           = ServiceId.of(GOOGLE, ServiceId.DEFAULT_INSTANCE, "google-ocr")
        private val DEFAULT_DICTIONARY    = ServiceId.of(GOOGLE, ServiceId.DEFAULT_INSTANCE, "google-dictionary")

        @OptIn(ExperimentalUuidApi::class)
        fun createDefault(name: String = DEFAULT_PRESET_NAME): ServicePreset = ServicePreset(
            id = Uuid.random().toString(),
            name = name,
            selectedServices = mapOf(
                ServiceRole.TRANSLATOR    to DEFAULT_TRANSLATOR,
                ServiceRole.TTS           to DEFAULT_TTS,
                ServiceRole.SPELL_CHECKER to DEFAULT_SPELL_CHECKER,
                ServiceRole.OCR           to DEFAULT_OCR,
                ServiceRole.DICTIONARY    to DEFAULT_DICTIONARY
            )
        )
    }
}
