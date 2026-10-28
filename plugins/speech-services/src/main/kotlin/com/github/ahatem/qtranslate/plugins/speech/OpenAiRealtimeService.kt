package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent
import com.github.ahatem.qtranslate.api.speech.LiveSpeechRecognizer
import com.github.ahatem.qtranslate.api.speech.LiveSpeechSession
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Realtime live speech transcription via OpenAI's Realtime WebSocket API (gpt-live-transcribe).
 *
 * Connects to wss://api.openai.com/v1/realtime?intent=transcription with a transcription session
 * type, sending 24 kHz mono PCM frames in input_audio_buffer.append events and receiving
 * interim and settled transcriptions.
 */
class OpenAiRealtimeService(
    private val settings: () -> SpeechSettings
) : LiveSpeechRecognizer {
    override val metadata = ServiceMetadata(requiresConfiguration = true)
    override val key: String = "openai-realtime"
    override val name: String = "OpenAI Realtime"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/speech.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    override suspend fun openSession(language: LanguageCode): Result<LiveSpeechSession, ServiceError> {
        val current = settings()
        val key = current.effectiveLiveApiKey
        if (key.isBlank()) {
            return Err(ServiceError.ConfigurationError("OpenAI Realtime requires an API key. Set it in Settings > Plugins > Speech Recognition."))
        }
        val model = current.liveModel.trim().ifEmpty { DEFAULT_MODEL }
        val langTag = SpeechClient.primaryTag(language)
        return OpenAiRealtimeSession.open(key, model, languageTag = langTag)
    }

    override suspend fun validate(): Result<Unit, ServiceError> {
        val current = settings()
        val key = current.effectiveLiveApiKey
        if (key.isBlank()) {
            return Err(ServiceError.ConfigurationError("OpenAI Realtime requires an API key. Set it in Settings > Plugins > Speech Recognition."))
        }
        val model = current.liveModel.trim().ifEmpty { DEFAULT_MODEL }
        return OpenAiRealtimeSession.ping(key, model)
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-live-transcribe"
    }
}

class OpenAiRealtimeSession private constructor(
    private val model: String,
    private val languageTag: String? = null
) : WebSocketLiveSession("OpenAI Realtime") {

    private val currentPartial = StringBuilder()
    private val resampler = LinearPcmResampler(16_000, 24_000)

    // Client-side VAD state for turn commits (gpt-live-transcribe has no server VAD)
    private var uncommittedAudioBytes: Long = 0L
    private var speechDetectedInCurrentTurn: Boolean = false
    private var consecutiveSilentSamples: Long = 0L
    private var currentTurnSamples: Long = 0L
    private var unvoicedSilentSamples: Long = 0L

    override fun sendAudio(pcm: ByteArray) {
        if (ended || pcm.isEmpty()) return
        val resampled = resampler.process(pcm)
        if (resampled.isEmpty()) return

        val b64 = Base64.getEncoder().encodeToString(resampled)
        enqueue { it.sendText("""{"type":"input_audio_buffer.append","audio":"$b64"}""", true) }
        uncommittedAudioBytes += resampled.size

        // Track energy on the input 16-bit PCM (resampled 24 kHz audio, mono 16-bit LE)
        val numSamples = resampled.size / 2
        val rms = calculateRms(resampled)
        val isSpeech = rms >= MIN_SPEECH_RMS

        if (isSpeech) {
            speechDetectedInCurrentTurn = true
            consecutiveSilentSamples = 0L
            unvoicedSilentSamples = 0L
            currentTurnSamples += numSamples
        } else if (speechDetectedInCurrentTurn) {
            consecutiveSilentSamples += numSamples
            currentTurnSamples += numSamples
        } else {
            unvoicedSilentSamples += numSamples
            val unvoicedSilenceMs = (unvoicedSilentSamples * 1000L) / RESAMPLED_SAMPLE_RATE
            if (unvoicedSilenceMs >= SILENCE_BUFFER_CLEAR_THRESHOLD_MS && uncommittedAudioBytes > 0) {
                enqueue { it.sendText("""{"type":"input_audio_buffer.clear"}""", true) }
                uncommittedAudioBytes = 0L
                unvoicedSilentSamples = 0L
            }
        }

        val silenceDurationMs = (consecutiveSilentSamples * 1000L) / RESAMPLED_SAMPLE_RATE
        val turnDurationMs = (currentTurnSamples * 1000L) / RESAMPLED_SAMPLE_RATE

        val shouldCommitSilence = speechDetectedInCurrentTurn && silenceDurationMs >= SILENCE_THRESHOLD_MS
        val shouldCommitMaxDuration = speechDetectedInCurrentTurn && turnDurationMs >= MAX_TURN_DURATION_MS

        if ((shouldCommitSilence || shouldCommitMaxDuration) && uncommittedAudioBytes > 0) {
            commitTurn()
        }
    }

    private fun commitTurn() {
        if (uncommittedAudioBytes > 0) {
            enqueue { it.sendText("""{"type":"input_audio_buffer.commit"}""", true) }
            uncommittedAudioBytes = 0L
        }
        speechDetectedInCurrentTurn = false
        consecutiveSilentSamples = 0L
        currentTurnSamples = 0L
        unvoicedSilentSamples = 0L
    }

    override suspend fun finish() {
        if (ended) return
        if (speechDetectedInCurrentTurn && uncommittedAudioBytes > 0) {
            enqueue { it.sendText("""{"type":"input_audio_buffer.commit"}""", true) }
            uncommittedAudioBytes = 0L
        }
        closeSocket()
    }

    private fun calculateRms(pcm: ByteArray): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += sample * sample
            i += 2
        }
        val samples = (pcm.size / 2).coerceAtLeast(1)
        return kotlin.math.sqrt(sum / samples)
    }

    private fun handleMessage(text: String) {
        val parsed = parseOpenAiRealtimeMessage(text)
        when (parsed) {
            is OpenAiParsedMessage.Ready -> ready.complete(Ok(Unit))
            is OpenAiParsedMessage.PartialDelta -> {
                currentPartial.append(parsed.delta)
                channel.trySend(LiveSpeechEvent.Partial(currentPartial.toString()))
            }
            is OpenAiParsedMessage.Final -> {
                currentPartial.setLength(0)
                channel.trySend(LiveSpeechEvent.Final(parsed.text, emptyList()))
            }
            is OpenAiParsedMessage.Error -> {
                val error = ServiceError.ServiceUnavailableError("OpenAI Realtime: ${parsed.message}")
                if (!ready.complete(Err(error))) end(error)
            }
            OpenAiParsedMessage.Ignored -> Unit
        }
    }

    internal fun buildSessionUpdateConfig(): String {
        val transcriptionProps = mutableListOf<String>()
        transcriptionProps.add(""""model":${openAiJson.encodeToString(kotlinx.serialization.serializer(), model)}""")
        if (!languageTag.isNullOrBlank()) {
            transcriptionProps.add(""""languages":[${openAiJson.encodeToString(kotlinx.serialization.serializer(), languageTag)}]""")
        }
        val transcriptionJson = transcriptionProps.joinToString(",")
        return """{"type":"session.update","session":{"type":"transcription","audio":{"input":{"format":{"type":"audio/pcm","rate":24000},"transcription":{$transcriptionJson},"turn_detection":null}}}}"""
    }

    private val listener = object : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            socket = webSocket
            // Configure dedicated transcription session (GA interface, no assistant turn response generation)
            val config = buildSessionUpdateConfig()
            enqueue { it.sendText(config, true) }
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val message = buffer.toString()
                buffer.setLength(0)
                handleMessage(message)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            val error = if (statusCode != WebSocket.NORMAL_CLOSURE) {
                ServiceError.NetworkError("OpenAI Realtime closed ($statusCode: $reason)")
            } else null
            ready.complete(Ok(Unit))
            end(error)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            val failure = ServiceError.NetworkError("OpenAI Realtime error: ${error.message}", error)
            ready.complete(Err(failure))
            end(failure)
        }
    }

    private suspend fun connect(apiKey: String, endpoint: String): Result<Unit, ServiceError> {
        val uri = URI(endpoint)
        return try {
            socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .newWebSocketBuilder()
                .header("Authorization", "Bearer $apiKey")
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri, listener).await()
            withTimeoutOrNull(10_000L) { ready.await() }
                ?: Err(ServiceError.TimeoutError("OpenAI Realtime session timed out waiting for ready event."))
        } catch (e: Exception) {
            Err(ServiceError.NetworkError("Could not reach OpenAI Realtime: ${e.cause?.message ?: e.message}", e))
        }
    }

    companion object {
        const val ENDPOINT = "wss://api.openai.com/v1/realtime?intent=transcription"

        /** Sample rate of resampled PCM audio sent to OpenAI (24 kHz). */
        const val RESAMPLED_SAMPLE_RATE = 24_000

        /** Silence duration following speech required to commit an input turn (~600 ms). */
        const val SILENCE_THRESHOLD_MS = 600L

        /** Maximum duration of an ongoing speech turn before committing (~15 s). */
        const val MAX_TURN_DURATION_MS = 15_000L

        /** Minimum RMS threshold to distinguish voiced speech from background silence (~-50 dBFS). */
        const val MIN_SPEECH_RMS = 250.0

        /** Threshold of silence before any speech at which uncommitted audio buffer is cleared (~3 s). */
        const val SILENCE_BUFFER_CLEAR_THRESHOLD_MS = 3_000L

        suspend fun open(
            apiKey: String,
            model: String = OpenAiRealtimeService.DEFAULT_MODEL,
            endpoint: String = ENDPOINT,
            languageTag: String? = null
        ): Result<LiveSpeechSession, ServiceError> {
            val session = OpenAiRealtimeSession(model, languageTag)
            session.connect(apiKey, endpoint).getError()?.let { return Err(it) }
            return Ok(session)
        }

        suspend fun ping(
            apiKey: String,
            model: String = OpenAiRealtimeService.DEFAULT_MODEL,
            endpoint: String = ENDPOINT
        ): Result<Unit, ServiceError> {
            val session = OpenAiRealtimeSession(model)
            val result = session.connect(apiKey, endpoint)
            session.cancel()
            return result
        }
    }
}

internal sealed interface OpenAiParsedMessage {
    data object Ready : OpenAiParsedMessage
    data class PartialDelta(val delta: String) : OpenAiParsedMessage
    data class Final(val text: String) : OpenAiParsedMessage
    data class Error(val message: String) : OpenAiParsedMessage
    data object Ignored : OpenAiParsedMessage
}

private val openAiJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseOpenAiRealtimeMessage(text: String): OpenAiParsedMessage {
    val json = runCatching { openAiJson.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return OpenAiParsedMessage.Ignored

    val type = json["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
    return when (type) {
        "session.created", "session.updated" -> OpenAiParsedMessage.Ready
        "conversation.item.input_audio_transcription.delta" -> {
            val delta = json["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (delta.isNotEmpty()) OpenAiParsedMessage.PartialDelta(delta) else OpenAiParsedMessage.Ignored
        }
        "conversation.item.input_audio_transcription.completed" -> {
            val transcript = json["transcript"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (transcript.isNotEmpty()) OpenAiParsedMessage.Final(transcript) else OpenAiParsedMessage.Ignored
        }
        "error" -> {
            val errorMsg = json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: text
            OpenAiParsedMessage.Error(errorMsg)
        }
        else -> OpenAiParsedMessage.Ignored
    }
}
