package com.github.ahatem.qtranslate.core.plugin.registry

import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.imagesearch.ImageSearch
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceCapability
import com.github.ahatem.qtranslate.api.rewriter.Rewriter
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.api.summarizer.Summarizer
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.api.tts.TextToSpeech

/**
 * Checks that a service actually implements the capabilities it declares.
 *
 * ### Why this exists
 * Capabilities used to be inferred from implemented interfaces, which meant a service could not
 * misrepresent itself — the type system settled it. Declaring capabilities gave services the
 * ability to advertise more than one, but it also made the claim unverified.
 *
 * Without this check a service declaring [ServiceCapability.TRANSLATOR] without implementing
 * [Translator] loads and enables cleanly, then throws `ClassCastException` the first time the
 * user translates something — because the host resolves services by declared capability and
 * casts to the matching interface.
 *
 * Catching it at registration turns a crash during use into a plugin that reports itself as
 * failed, which the plugin manager already knows how to display.
 */
internal object CapabilityValidator {

    private val REQUIRED: Map<ServiceCapability, Class<*>> = mapOf(
        ServiceCapability.TRANSLATOR to Translator::class.java,
        ServiceCapability.TTS to TextToSpeech::class.java,
        ServiceCapability.OCR to OCR::class.java,
        ServiceCapability.SPELL_CHECKER to SpellChecker::class.java,
        ServiceCapability.DICTIONARY to Dictionary::class.java,
        ServiceCapability.SUMMARIZER to Summarizer::class.java,
        ServiceCapability.REWRITER to Rewriter::class.java,
        ServiceCapability.IMAGE_SEARCH to ImageSearch::class.java
    )

    /**
     * Returns a human-readable description of every problem found, or an empty list when the
     * service is consistent.
     *
     * Reports all mismatches rather than stopping at the first, so a plugin author fixes them
     * in one pass instead of discovering them one build at a time.
     */
    fun validate(service: Service): List<String> {
        val problems = mutableListOf<String>()

        if (service.key.isBlank()) {
            problems += "service key is blank"
        }

        if (service.capabilities.isEmpty()) {
            problems += "service '${service.key}' declares no capabilities, so it can never be selected"
        }

        service.capabilities.forEach { capability ->
            val required = REQUIRED[capability]
            if (required == null) {
                // A capability this host build does not know about. Not an error: the plugin may
                // target a newer host, and the extra capability is simply never offered here.
                return@forEach
            }
            if (!required.isAssignableFrom(service::class.java)) {
                problems += "service '${service.key}' declares ${capability.name} " +
                        "but does not implement ${required.simpleName}"
            }
        }

        return problems
    }

    /** Validates every service, returning problems keyed by the service that caused them. */
    fun validateAll(services: List<Service>): List<String> = services.flatMap(::validate)
}
