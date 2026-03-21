package com.github.ahatem.qtranslate.api.tts

import com.github.ahatem.qtranslate.api.language.LanguageCode

/**
 * Optional capability interface for [TextToSpeech] services that support explicit voice selection.
 *
 * ### How this connects to TTSRequest
 * [TTSRequest] is a sealed interface with two variants:
 * - [TTSRequest.ByLanguage] — for basic TTS services. The service picks a default voice.
 * - [TTSRequest.ByVoice] — for services that implement **this** interface. The caller
 *   selects a specific [Voice] from [voices].
 *
 * **The core guarantees** that [TTSRequest.ByVoice] is only ever dispatched to a
 * [TextToSpeech] service that also implements [VoiceSupport]. A service that does not
 * implement [VoiceSupport] will never receive a [TTSRequest.ByVoice] request — the core
 * validates this via [com.github.ahatem.qtranslate.api.plugin.Service.getCapability]
 * before dispatching.
 *
 * ### Implementing VoiceSupport
 * A service implements both [TextToSpeech] and [VoiceSupport]:
 * ```kotlin
 * class MyTTSService : TextToSpeech, VoiceSupport {
 *     override val voices: List<Voice> = listOf(
 *         Voice(id = "en-US-AriaNeural", name = "Aria", language = LanguageCode.ENGLISH, gender = Gender.FEMALE),
 *         Voice(id = "en-GB-SoniaNeural", name = "Sonia", language = LanguageCode("en-GB"), gender = Gender.FEMALE)
 *     )
 *
 *     override suspend fun synthesize(request: TTSRequest): Result<TTSResponse, ServiceError> =
 *         when (request) {
 *             is TTSRequest.ByVoice    -> synthesizeWithVoice(request.text, request.voice, request.speed)
 *             is TTSRequest.ByLanguage -> synthesizeWithVoice(request.text, defaultVoiceFor(request.language), request.speed)
 *         }
 * }
 * ```
 *
 * ### Discovery
 * The core discovers this capability without casting:
 * ```kotlin
 * val voiceSupport = service.getCapability(VoiceSupport::class.java)
 * val availableVoices = voiceSupport?.voices ?: emptyList()
 * ```
 */
interface VoiceSupport {
    /**
     * The complete list of voices this service can synthesize with.
     *
     * This must be a fast, synchronous property — it is read by the core for UI rendering
     * (populating voice dropdowns) and must not perform any I/O. If the voice list can only
     * be determined at runtime (e.g. fetched from an API), fetch and cache it during
     * [com.github.ahatem.qtranslate.api.plugin.Plugin.onEnable] and return the cached list here.
     */
    val voices: List<Voice>
}

/**
 * Represents a single synthesizable voice provided by a [VoiceSupport] service.
 *
 * @param id       The stable, machine-readable voice identifier used in API requests.
 *                 Must be unique within a service. This is what gets passed back in
 *                 [TTSRequest.ByVoice.voice].
 * @param name     The human-readable voice name shown in the UI (e.g. `"Aria"`, `"Sonia"`).
 * @param language The primary language this voice speaks, as a [LanguageCode].
 * @param gender   The voice's gender, or `null` if unknown or not applicable.
 */
data class Voice(
    val id: String,
    val name: String,
    val language: LanguageCode,
    val gender: Gender? = null
)

/** The gender of a [Voice], used for filtering and display in the UI. */
enum class Gender {
    MALE, FEMALE, NEUTRAL
}