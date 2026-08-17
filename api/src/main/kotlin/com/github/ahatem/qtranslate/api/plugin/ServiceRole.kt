package com.github.ahatem.qtranslate.api.plugin

import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.imagesearch.ImageSearch
import com.github.ahatem.qtranslate.api.rewriter.Rewriter
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.api.summarizer.Summarizer
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.api.tts.TextToSpeech
import kotlin.reflect.KClass

/**
 * What a [Service] is for, and therefore which part of the application can offer it.
 *
 * ### A role is its interface
 * Every role names the contract a service must implement to hold it. That is the whole definition:
 * a service has the [TRANSLATOR] role exactly when it implements [Translator]. Nothing is declared
 * and nothing needs checking, because there is only one fact and [of] reads it.
 *
 * This replaced a design where a service declared a set of capabilities *and* implemented the
 * matching interfaces. Two statements of one fact can disagree, and the host resolves a service by
 * the declared value and then casts to the interface, so disagreeing meant a `ClassCastException`
 * the first time the user tried to use it. A validator existed purely to catch that, and it is
 * gone with the duplication that made it necessary.
 *
 * Declaring came in to fix a real bug: the roles were previously inferred by an *ordered* type
 * test, which returned one answer, so a service that both translated and defined words was filed
 * under whichever branch happened to run first. The flaw was the ordering, not the inference.
 * [of] filters instead of matching, so a service holds every role it implements.
 *
 * ### Roles against optional behaviour
 * A role is selectable by the user: they pick which translator translates. Behaviour that merely
 * varies *how* a service works, such as voice selection or batching, is not a role. It is an extra
 * interface the service may also implement, and the host asks with an ordinary `as?`:
 *
 * ```kotlin
 * val voices = (service as? VoiceSupport)?.voices.orEmpty()
 * ```
 *
 * ### Adding a role
 * Adding a constant is additive: a host that does not know it simply never offers that role, so it
 * needs a MINOR version bump rather than a MAJOR one. See
 * [com.github.ahatem.qtranslate.api.core.ApiVersion].
 */
public enum class ServiceRole(
    /** The interface a service implements to hold this role. */
    public val contract: KClass<out Service>
) {
    TRANSLATOR(Translator::class),
    TTS(TextToSpeech::class),

    // Written out in full because the constant and the interface share a name, and inside the
    // enum body the constant wins.
    OCR(com.github.ahatem.qtranslate.api.ocr.OCR::class),
    SPELL_CHECKER(SpellChecker::class),
    DICTIONARY(Dictionary::class),
    SUMMARIZER(Summarizer::class),
    REWRITER(Rewriter::class),

    /** Looks up images for a term, so a learner can see what it refers to. */
    IMAGE_SEARCH(ImageSearch::class);

    /** Whether [service] fulfils this role's contract. */
    public fun isHeldBy(service: Service): Boolean = contract.isInstance(service)

    public companion object {

        /**
         * Every role [service] holds.
         *
         * Empty when it implements no role interface, which the host reads as "never selectable".
         * That is a plugin bug rather than a state to design around, and it is reported at load.
         */
        public fun of(service: Service): Set<ServiceRole> =
            entries.filterTo(LinkedHashSet()) { it.isHeldBy(service) }
    }
}
