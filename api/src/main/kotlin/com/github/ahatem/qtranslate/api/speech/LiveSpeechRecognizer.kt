package com.github.ahatem.qtranslate.api.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.Flow

/**
 * A service that transcribes speech as it is spoken, from a continuous stream of audio.
 *
 * Where a [SpeechRecognizer] gets a finished clip and answers once, a live recognizer is fed audio
 * the moment it is recorded and keeps answering: a [LiveSpeechEvent.Partial] guess that firms up
 * word by word, then a [LiveSpeechEvent.Final] when the speaker pauses. That is what makes text
 * appear while someone is still talking (Vosk, Deepgram streaming, and the like).
 *
 * The two can work together: the host shows the live text at once and, when a [SpeechRecognizer]
 * is also active, replaces each finished line with that service's usually more accurate version.
 */
public interface LiveSpeechRecognizer : Service {

    /**
     * Opens a session for [language]. [LanguageCode.AUTO] lets the service pick, where it can.
     *
     * Return [ServiceError.ConfigurationError] when the service has not been set up; the host then
     * records without live text and says nothing, since a live recognizer is an optional extra.
     */
    public suspend fun openSession(language: LanguageCode): Result<LiveSpeechSession, ServiceError>
}

/**
 * Optional capability for a speech recognizer that relies on a catalogue of downloadable offline models.
 */
public interface ModelCatalogueCapability {
    /** All languages offered in the model catalogue, regardless of download state. */
    public val catalogueLanguages: Set<LanguageCode> get() = emptySet()

    /** Whether an offline model for [language] is currently installed and ready for transcription. */
    public fun isModelInstalled(language: LanguageCode): Boolean = true
}

/**
 * One live recognition session.
 *
 * Audio is always 16-bit little-endian PCM, mono, at 16 kHz, in chunks of any size. Times in
 * events count from the first sample sent to this session.
 */
public interface LiveSpeechSession {

    /** What the service heard, in order. Completes when the session has ended for any reason. */
    public val events: Flow<LiveSpeechEvent>

    /** Queues [pcm] for recognition. Never blocks; ignored once the session has ended. */
    public fun sendAudio(pcm: ByteArray)

    /** Asks for the final result of everything sent so far, then ends the session. */
    public suspend fun finish()

    /** Ends the session at once, discarding anything not yet recognised. */
    public fun cancel()
}

public sealed interface LiveSpeechEvent {
    /** The service's current guess at the words since the last [Final]. Replaces the previous one. */
    public data class Partial(val text: String, val words: List<RecognizedWord> = emptyList()) : LiveSpeechEvent {
        public constructor(text: String) : this(text, emptyList())
    }

    /**
     * A settled stretch of speech.
     *
     * @param words With times counted from the start of the session, when the service reports them.
     */
    public data class Final(val text: String, val words: List<RecognizedWord> = emptyList()) : LiveSpeechEvent

    /** The session broke (connection lost, server error). No further events follow. */
    public data class Failed(val error: ServiceError) : LiveSpeechEvent
}
