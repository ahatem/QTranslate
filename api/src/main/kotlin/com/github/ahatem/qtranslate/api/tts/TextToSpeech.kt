package com.github.ahatem.qtranslate.api.tts

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * A service that synthesizes speech audio from text.
 *
 * ### Request Types
 * [TTSRequest] is a sealed interface with two distinct variants to eliminate the
 * ambiguous `voice`/`language` mutual exclusion that existed in a single flat class:
 *
 * - [TTSRequest.ByLanguage] — for basic TTS services without voice selection.
 *   The service picks a default voice for the given language.
 * - [TTSRequest.ByVoice] — for services implementing [VoiceSupport], where the
 *   caller selects a specific voice from [VoiceSupport.voices].
 *
 * The core is responsible for ensuring that [TTSRequest.ByVoice] is only dispatched
 * to services that implement [VoiceSupport]. A service that does not implement
 * [VoiceSupport] will never receive a [TTSRequest.ByVoice] request.
 *
 * ### Optional Capability
 * Services with voice selection support should additionally implement [VoiceSupport].
 * The core discovers this via [Service.getCapability]:
 * ```kotlin
 * val voiceSupport = service.getCapability(VoiceSupport::class.java)
 * ```
 */
interface TextToSpeech : Service {

    /**
     * Synthesizes speech audio from the given [request].
     *
     * Implementations should return [ServiceError.InvalidInputError] for empty text,
     * [ServiceError.UnsupportedLanguageError] for unsupported languages, and
     * [ServiceError.NetworkError] for connectivity failures.
     *
     * @param request Either a [TTSRequest.ByLanguage] or [TTSRequest.ByVoice] request.
     * @return `Ok` containing the synthesized [TTSResponse], or an `Err` with a [ServiceError].
     */
    suspend fun synthesize(request: TTSRequest): Result<TTSResponse, ServiceError>
}

/**
 * Describes a speech synthesis request. Use the appropriate subtype based on
 * whether the caller is selecting a language or a specific voice.
 */
sealed interface TTSRequest {

    /** The text to synthesize. Must not be blank. */
    val text: String

    /**
     * Playback speed multiplier. `1.0` is normal speed.
     * Acceptable range is typically `0.5` to `2.0`, though service limits may vary.
     */
    val speed: Float

    /**
     * A request for a basic TTS service that does not support voice selection.
     * The service chooses a default voice for the given [language].
     *
     * Use this with services that do **not** implement [VoiceSupport].
     *
     * @param text     The text to synthesize. Must not be blank.
     * @param language The target language for synthesis.
     * @param speed    Playback speed multiplier. Defaults to `1.0` (normal speed).
     */
    data class ByLanguage(
        override val text: String,
        val language: LanguageCode,
        override val speed: Float = 1.0f
    ) : TTSRequest {
        init {
            require(text.isNotBlank()) { "TTS request text must not be blank." }
            require(speed > 0f) { "TTS speed must be positive, was $speed." }
        }
    }

    /**
     * A request for a TTS service that supports voice selection ([VoiceSupport]).
     * The caller selects a specific [Voice] from [VoiceSupport.voices].
     *
     * This variant is only dispatched by the core to services implementing [VoiceSupport].
     * The language is derived from [Voice.language] — no separate language field is needed.
     *
     * @param text  The text to synthesize. Must not be blank.
     * @param voice The specific voice to use, sourced from [VoiceSupport.voices].
     * @param speed Playback speed multiplier. Defaults to `1.0` (normal speed).
     */
    data class ByVoice(
        override val text: String,
        val voice: Voice,
        override val speed: Float = 1.0f
    ) : TTSRequest {
        init {
            require(text.isNotBlank()) { "TTS request text must not be blank." }
            require(speed > 0f) { "TTS speed must be positive, was $speed." }
        }
    }
}

/**
 * The result of a successful speech synthesis operation.
 *
 * @param audio The synthesized audio, either as raw [TTSAudio.Bytes] or a [TTSAudio.StreamUrl].
 */
data class TTSResponse(
    val audio: TTSAudio
)

/**
 * The synthesized audio payload. Services return whichever variant their API provides.
 *
 * - [Bytes] — for APIs that return a complete audio file in the response body.
 * - [StreamUrl] — for APIs that return a URL to stream or download the audio from.
 */
sealed class TTSAudio {
    abstract val format: AudioFormat

    /**
     * A complete audio file returned as raw bytes.
     * @param data   The raw audio bytes.
     * @param format The encoding format of the audio.
     */
    data class Bytes(val data: ByteArray, override val format: AudioFormat) : TTSAudio() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Bytes
            return data.contentEquals(other.data) && format == other.format
        }
        override fun hashCode(): Int = 31 * data.contentHashCode() + format.hashCode()
    }

    /**
     * A URL pointing to a streamable or downloadable audio resource.
     * @param url    The fully qualified URL.
     * @param format The encoding format of the audio at the URL.
     */
    data class StreamUrl(val url: String, override val format: AudioFormat) : TTSAudio()
}

/** The audio encoding format of a [TTSAudio] payload. */
enum class AudioFormat {
    MP3, WAV, OGG
}