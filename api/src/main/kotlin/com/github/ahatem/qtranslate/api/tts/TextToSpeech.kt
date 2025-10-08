package com.github.ahatem.qtranslate.api.tts

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.language.LanguageSupport
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * A service that synthesizes audio from text.
 */
interface TextToSpeech : Service, LanguageSupport {
    suspend fun synthesize(request: TTSRequest): Result<TTSResponse, ServiceError>
}



data class TTSRequest(
    val text: String,
    val voice: Voice? = null, // Null if voices are not supported
    val speed: Float = 1.0f
)

data class TTSResponse(
    val audio: TTSAudio
)

sealed class TTSAudio {
    abstract val format: AudioFormat

    data class Bytes(val data: ByteArray, override val format: AudioFormat) : TTSAudio() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Bytes
            if (!data.contentEquals(other.data)) return false
            if (format != other.format) return false
            return true
        }

        override fun hashCode(): Int = 31 * data.contentHashCode() + format.hashCode()
    }

    data class StreamUrl(val url: String, override val format: AudioFormat) : TTSAudio()
}

enum class AudioFormat {
    MP3, WAV, OGG
}