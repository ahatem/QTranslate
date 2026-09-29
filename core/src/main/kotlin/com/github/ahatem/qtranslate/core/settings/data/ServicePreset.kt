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
        //
        // Each role is chosen independently rather than defaulting everything to one vendor:
        //
        // - Translator and TTS stay on Google. Translator has a three-tier fallback (an optional
        //   official API, an unofficial primary endpoint, and a separate unofficial fallback
        //   endpoint) plus a circuit breaker between the last two, and TTS calls its own dedicated
        //   audio endpoint -- neither needs a key, and both have held up well in practice.
        // - Spell Checker and Dictionary move to System Services / Wiktionary. Google's versions of
        //   both piggyback on the same unofficial translate_a/single endpoint used for Translator,
        //   but with no fallback endpoint and no official alternative -- Dictionary doesn't even
        //   check the shared circuit breaker before calling it. That endpoint can and does get
        //   rate-limited independently of Translator's own health (reproduced directly: it returned
        //   Google's automated-query block page for both while the TTS and Translator-fallback
        //   endpoints kept working). System Spell Checker needs no network or key at all; Wiktionary
        //   is a real, official, rate-limit-aware REST API rather than an inferred side channel.
        // - OCR moves to System OCR. Google OCR calls the real Cloud Vision API, but only registers
        //   when a Vision API key is configured -- on every fresh install it is simply absent, so
        //   today's "default" never actually resolves to it; the role silently falls back to
        //   whatever else is usable. System OCR makes that fallback the deliberate default instead:
        //   no key, no network, and it degrades the same way today's accidental fallback already
        //   does when unavailable.
        //
        // All four bundled plugins referenced here ship enabled by default, so a fresh install
        // resolves every role without the user configuring anything.
        private const val GOOGLE = "google-services"
        private const val SYSTEM = "system-services"
        private const val WIKIMEDIA = "wikimedia-reference"
        private val DEFAULT_TRANSLATOR    = ServiceId.of(GOOGLE, ServiceId.DEFAULT_INSTANCE, "google-translator")
        private val DEFAULT_TTS           = ServiceId.of(GOOGLE, ServiceId.DEFAULT_INSTANCE, "google-tts")
        private val DEFAULT_SPELL_CHECKER = ServiceId.of(SYSTEM, ServiceId.DEFAULT_INSTANCE, "system-spell-checker")
        private val DEFAULT_OCR           = ServiceId.of(SYSTEM, ServiceId.DEFAULT_INSTANCE, "system-ocr")
        private val DEFAULT_DICTIONARY    = ServiceId.of(WIKIMEDIA, ServiceId.DEFAULT_INSTANCE, "wikimedia-wiktionary")

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
