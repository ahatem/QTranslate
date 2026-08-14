package com.github.ahatem.qtranslate.api.plugin

/**
 * What a [Service] can do.
 *
 * ### Declared, not inferred
 * A service states its capabilities through [Service.capabilities]. The host does **not** guess
 * them from implemented interfaces. Inference by type test cannot express a service that does
 * two things — it silently classifies such a service as whichever check happens to run first —
 * so a service that is both a translator and a dictionary declares both here and is offered
 * under both.
 *
 * ### Adding a capability
 * New constants are additive: a host that does not know a constant simply never offers that
 * service for it. Adding one therefore needs a MINOR version bump, not a MAJOR one. See
 * [com.github.ahatem.qtranslate.api.core.ApiVersion].
 *
 * ### Capability vs optional behaviour
 * A capability is *what a service is for* and is selectable by the user. Optional behaviour —
 * streaming, batching, voice selection — is *how a service does its job* and belongs in a
 * queryable mix-in discovered through [Service.getCapability], not here.
 */
enum class ServiceCapability {
    TRANSLATOR,
    TTS,
    OCR,
    SPELL_CHECKER,
    DICTIONARY,
    SUMMARIZER,
    REWRITER,

    /** Looks up images for a term, so a learner can see what it refers to. */
    IMAGE_SEARCH
}
