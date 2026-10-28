package com.github.ahatem.qtranslate.core.conversation

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.speech.LiveSpeechSession
import com.github.ahatem.qtranslate.api.speech.SpeechAudio
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionRequest
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionResponse
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.Result
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Calls the active speech service, or answers `null` when none is active. A function rather than
 * the service itself, so the active one is looked up per clip and a switch in settings applies
 * mid-session.
 */
typealias Recognize = suspend (SpeechRecognitionRequest) -> Result<SpeechRecognitionResponse, ServiceError>?

/**
 * Opens a session with the active live recognizer, or answers `null` when none is active. Looked
 * up per session for the same reason as [Recognize].
 */
typealias OpenLive = suspend (LanguageCode) -> Result<LiveSpeechSession, ServiceError>?

/** Turns one recorded utterance into a [TranscriptSegment] with its words placed on the session timeline. */
class SegmentTranscriber(
    private val recognize: Recognize,
    private val timeoutMillis: Long = 60_000
) {
    sealed interface Outcome {
        val segment: TranscriptSegment

        data class Done(override val segment: TranscriptSegment) : Outcome
        data class Failed(override val segment: TranscriptSegment, val error: ServiceError) : Outcome
        data class NoService(override val segment: TranscriptSegment) : Outcome
    }

    /**
     * @param placeholder The pending segment this utterance was announced as; its id, source and
     *                    timing are kept.
     * @param pcm         The utterance, 16-bit mono at [PcmStream.SAMPLE_RATE].
     * @param prompt      What was said just before, so names and terms stay consistent.
     */
    suspend fun transcribe(
        placeholder: TranscriptSegment,
        pcm: ByteArray,
        language: LanguageCode,
        prompt: String?
    ): Outcome {
        val request = SpeechRecognitionRequest(
            audio = SpeechAudio(WavFiles.encode(pcm)),
            language = language,
            prompt = prompt
        )
        var noService = false
        val result = withTimeoutOrNull(timeoutMillis) {
            recognize(request).also { if (it == null) noService = true }
        }
        return when {
            noService -> Outcome.NoService(placeholder.asFailed())
            result == null -> Outcome.Failed(placeholder.asFailed(), ServiceError.TimeoutError("Transcription timed out."))
            else -> result.fold(
                success = { Outcome.Done(placeholder.withResponse(it)) },
                failure = { Outcome.Failed(placeholder.asFailed(), it) }
            )
        }
    }

    private fun TranscriptSegment.asFailed() = copy(pending = false, failed = true, draft = false)

    private fun TranscriptSegment.withResponse(response: SpeechRecognitionResponse) = copy(
        text = response.text.trim(),
        language = response.detectedLanguage?.tag ?: language,
        words = response.words.map { word ->
            TranscriptWord(
                text = word.text,
                startMillis = startMillis + word.startMillis,
                endMillis = (startMillis + word.endMillis).coerceAtMost(endMillis),
                confidence = word.confidence
            )
        },
        pending = false,
        failed = false,
        draft = false
    )
}
