package com.github.ahatem.qtranslate.api

import com.github.michaelbull.result.Result

/**
 * A service that synthesizes audio from text.
 */
interface TextToSpeech : Service, LanguageSupport {
    /**
     * A list of available voices that this service provides.
     * The core can use this to populate a selection UI for the user.
     */
    val voices: List<Voice>

    /**
     * Converts the given text into speech audio.
     */
    suspend fun synthesize(request: TTSRequest): Result<TTSResponse, ServiceError>
}

data class Voice(
    val id: String,
    val name: String,
    val language: LanguageCode,
    val gender: Gender? = null
)

enum class Gender {
    MALE,
    FEMALE,
    NEUTRAL
}

data class TTSRequest(
    val text: String,
    /** The specific voice to use for synthesis. */
    val voice: Voice,
    /** The speed of the speech, where 1.0 is normal. */
    val speed: Float = 1.0f // e.g., range from 0.5 to 2.0
)

/**
 * The successful response from a TTS synthesis, containing the audio content.
 */
data class TTSResponse(
    val audio: TTSAudio
)

/**
 * A sealed class representing the two possible forms of TTS audio content.
 */
sealed class TTSAudio {
    /** The format of the audio data (e.g., MP3, WAV). */
    abstract val format: AudioFormat

    /**
     * Represents audio content provided as a raw byte array.
     */
    data class Bytes(val data: ByteArray, override val format: AudioFormat) : TTSAudio() {
        // Correctly implemented equals and hashCode for ByteArray content.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Bytes // Corrected cast
            if (!data.contentEquals(other.data)) return false
            if (format != other.format) return false
            return true
        }

        override fun hashCode(): Int {
            return 31 * data.contentHashCode() + format.hashCode()
        }
    }

    /**
     * Represents audio content provided as a URL to a stream or file.
     */
    data class StreamUrl(val url: String, override val format: AudioFormat) : TTSAudio()
}

enum class AudioFormat {
    MP3,
    WAV,
    OGG
}