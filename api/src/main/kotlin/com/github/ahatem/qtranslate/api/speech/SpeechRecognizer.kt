package com.github.ahatem.qtranslate.api.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * A service that turns recorded speech into text.
 *
 * The host does the recording. It captures audio, cuts it into utterances at natural pauses and
 * hands each one over as a complete [SpeechAudio] clip, so an implementation never deals with
 * devices, streaming or silence detection — it transcribes one short clip and returns.
 *
 * Implementations should return [ServiceError.InvalidInputError] for an empty or unreadable clip,
 * [ServiceError.UnsupportedLanguageError] for a language they cannot recognise, and
 * [ServiceError.NetworkError] for connectivity failures. A clip that holds no speech is not an
 * error: return an empty [SpeechRecognitionResponse.text] and the host discards it.
 */
public interface SpeechRecognizer : Service {

    /**
     * Transcribes [request].
     *
     * @return `Ok` with the recognised text, or an `Err` with a [ServiceError].
     */
    public suspend fun transcribe(
        request: SpeechRecognitionRequest
    ): Result<SpeechRecognitionResponse, ServiceError>
}

/**
 * Optional capability for a [SpeechRecognizer] indicating whether it provides independent verification
 * of spoken audio when paired with live recognition.
 *
 * When `false` (e.g. in Local only mode where both live recognition and batch transcription use the
 * same local Vosk model), rolling verification is skipped to avoid dishonest self-verification badges
 * and unnecessary duplicate CPU work.
 */
public interface VerificationCapability {
    /**
     * Whether this recognizer provides an independent verification source.
     * Defaults to true for remote/cloud providers.
     */
    public val isIndependentVerifier: Boolean get() = true
}

/**
 * One utterance to transcribe.
 *
 * @param audio    The recorded clip.
 * @param language The language being spoken, or [LanguageCode.AUTO] to let the service detect it.
 *                 Naming it, when known, noticeably improves accuracy on short clips.
 * @param prompt   Optional text that preceded this clip in the same conversation. Services that
 *                 accept context use it to keep names, terms and spelling consistent between
 *                 utterances; others ignore it.
 */
public data class SpeechRecognitionRequest(
    val audio: SpeechAudio,
    val language: LanguageCode = LanguageCode.AUTO,
    val prompt: String? = null
)

/**
 * The outcome of a transcription.
 *
 * @param text             The recognised text. Blank when the clip held no speech.
 * @param detectedLanguage The language the service heard, when it reports one.
 */
public data class SpeechRecognitionResponse(
    val text: String,
    val detectedLanguage: LanguageCode? = null,
    /**
     * Each recognised word with where it falls in the clip, when the service reports timings.
     * Lets the host replay a single word. Empty when the service has no word timestamps.
     */
    val words: List<RecognizedWord> = emptyList()
)

/**
 * One recognised word and its position in the clip.
 *
 * @param startMillis Offset from the start of the clip.
 * @param endMillis   Offset from the start of the clip; never before [startMillis].
 * @param confidence  Optional recognizer confidence score in `0.0..1.0`, when reported.
 */
public data class RecognizedWord(
    val text: String,
    val startMillis: Long,
    val endMillis: Long,
    val confidence: Double? = null
) {
    public constructor(text: String, startMillis: Long, endMillis: Long) : this(text, startMillis, endMillis, null)
}

/**
 * A complete recorded clip.
 *
 * The host always sends [AudioEncoding.WAV]: 16-bit little-endian PCM, mono, at [sampleRate],
 * with a standard RIFF header — the format every speech API accepts without conversion.
 *
 * @param bytes      The encoded file, header included.
 * @param encoding   How [bytes] is encoded.
 * @param sampleRate Samples per second.
 */
public class SpeechAudio(
    public val bytes: ByteArray,
    public val encoding: AudioEncoding = AudioEncoding.WAV,
    public val sampleRate: Int = 16_000
) {
    init {
        require(bytes.isNotEmpty()) { "Speech audio must not be empty." }
        require(sampleRate > 0) { "Sample rate must be positive, was $sampleRate." }
    }

    /** Length of the clip in milliseconds, derived from the PCM payload. */
    public val durationMillis: Long
        get() = ((bytes.size - WAV_HEADER_SIZE).coerceAtLeast(0) / 2L) * 1000L / sampleRate

    private companion object {
        const val WAV_HEADER_SIZE = 44
    }
}

/** How a [SpeechAudio] clip is encoded. */
public enum class AudioEncoding(
    /** The file extension a service expects in an upload, without the dot. */
    public val extension: String,
    /** The media type to declare in an upload. */
    public val mimeType: String
) {
    WAV("wav", "audio/wav")
}
