package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent
import com.github.ahatem.qtranslate.api.speech.LiveSpeechRecognizer
import com.github.ahatem.qtranslate.api.speech.LiveSpeechSession
import com.github.ahatem.qtranslate.api.speech.RecognizedWord
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Realtime live transcription via Deepgram's streaming WebSocket API.
 *
 * Streams 16 kHz raw PCM audio and receives partial and final transcripts with word timings.
 */
class DeepgramLiveService(
    private val settings: () -> SpeechSettings
) : LiveSpeechRecognizer {
    override val metadata = ServiceMetadata(requiresConfiguration = true)
    override val key: String = "deepgram-live"
    override val name: String = "Deepgram (live)"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/speech.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    override suspend fun openSession(language: LanguageCode): Result<LiveSpeechSession, ServiceError> {
        val current = settings()
        val key = current.effectiveLiveApiKey
        if (key.isBlank()) {
            return Err(ServiceError.ConfigurationError("Deepgram live requires an API key. Set it in Settings > Plugins > Speech Recognition."))
        }
        val lang = SpeechClient.primaryTag(language)
        val model = current.liveModel.trim().ifEmpty { "nova-3" }

        // P7: Verify model supports language or multi
        validateLanguageForModel(lang, model)?.let { return Err(it) }

        val serverHost = current.baseUrl.trim().takeIf { it.isNotBlank() }?.let { url ->
            runCatching { URI(url).host }.getOrNull()
        }
        return DeepgramLiveSession.open(key, lang, model, host = serverHost)
    }

    override suspend fun validate(): Result<Unit, ServiceError> {
        val current = settings()
        val key = current.effectiveLiveApiKey
        if (key.isBlank()) {
            return Err(ServiceError.ConfigurationError("Deepgram live requires an API key. Set it in Settings > Plugins > Speech Recognition."))
        }
        val model = current.liveModel.trim().ifEmpty { "nova-3" }
        val serverHost = current.baseUrl.trim().takeIf { it.isNotBlank() }?.let { url ->
            runCatching { URI(url).host }.getOrNull()
        }
        return DeepgramLiveSession.ping(key, model, host = serverHost)
    }

    companion object {
        private val MULTI_SUPPORTED_MODELS = setOf("nova-3", "nova-3-general", "nova-3-medical", "nova-2", "nova-2-general", "flux-general-multi")

        fun validateLanguageForModel(lang: String?, model: String): ServiceError? {
            val normalizedModel = model.lowercase()
            if (lang == null || lang == "auto") {
                if (MULTI_SUPPORTED_MODELS.none { normalizedModel.startsWith(it) }) {
                    return ServiceError.ConfigurationError(
                        SpeechLocalization.get("deepgram_unsupported_multi", model),
                        null,
                        ServiceError.ConfigurationError.Reason.MODEL_NOT_INSTALLED
                    )
                }
            }
            return null
        }
    }
}

internal fun buildDeepgramQuery(lang: String?, model: String): String = buildString {
    append("encoding=linear16&sample_rate=16000&channels=1&interim_results=true&smart_format=true")
    val encodedModel = java.net.URLEncoder.encode(model, "UTF-8")
    append("&model=").append(encodedModel)
    if (!lang.isNullOrBlank() && lang != "auto") {
        append("&language=").append(lang)
    } else {
        append("&language=multi")
    }
}

class DeepgramLiveSession private constructor() : WebSocketLiveSession("Deepgram") {

    private val sessionScope = CoroutineScope(Dispatchers.IO)
    private var keepAliveJob: Job? = null

    override fun sendAudio(pcm: ByteArray) {
        if (ended || pcm.isEmpty()) return
        enqueue { it.sendBinary(ByteBuffer.wrap(pcm), true) }
    }

    override suspend fun finish() {
        if (ended) return
        keepAliveJob?.cancel()
        keepAliveJob = null
        // Deepgram ends stream with an empty binary frame or CloseStream message
        enqueue { it.sendText("""{"type":"CloseStream"}""", true) }
        closeSocket()
    }

    override fun cancel() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        super.cancel()
    }

    override fun end(failure: ServiceError?) {
        keepAliveJob?.cancel()
        keepAliveJob = null
        super.end(failure)
    }

    private fun handleMessage(text: String) {
        val parsed = parseDeepgramMessage(text)
        when (parsed) {
            is DeepgramParsedMessage.Partial -> channel.trySend(LiveSpeechEvent.Partial(parsed.text, parsed.words))
            is DeepgramParsedMessage.Final -> channel.trySend(LiveSpeechEvent.Final(parsed.text, parsed.words))
            is DeepgramParsedMessage.Error -> end(ServiceError.ServiceUnavailableError("Deepgram error: ${parsed.message}"))
            DeepgramParsedMessage.Ignored -> Unit
        }
    }

    private val listener = object : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            socket = webSocket
            ready.complete(Ok(Unit))
            keepAliveJob = sessionScope.launch {
                while (isActive && !ended) {
                    delay(5_000)
                    if (ended || !isActive) break
                    enqueue { it.sendText("""{"type":"KeepAlive"}""", true) }
                }
            }
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
                ServiceError.NetworkError("Deepgram live closed ($statusCode: $reason)")
            } else null
            ready.complete(Ok(Unit))
            end(error)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            val failure = ServiceError.NetworkError("Deepgram live connection failure: ${error.message}", error)
            ready.complete(Err(failure))
            end(failure)
        }
    }

    private suspend fun connect(apiKey: String, lang: String?, model: String, host: String? = null): Result<Unit, ServiceError> {
        val query = buildDeepgramQuery(lang, model)
        val endpointHost = host?.takeIf { it.isNotBlank() } ?: "api.deepgram.com"
        val uri = URI("wss://$endpointHost/v1/listen?$query")
        return try {
            socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .newWebSocketBuilder()
                .header("Authorization", "Token $apiKey")
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri, listener).await()
            ready.await()
        } catch (e: Exception) {
            val status = extractHttpStatus(e)
            val errorMsg = when (status) {
                401 -> "Deepgram live rejected API key (HTTP 401 Unauthorized)"
                400 -> "Deepgram live rejected parameters or model '$model' (HTTP 400 Bad Request)"
                else -> "Could not connect to Deepgram live: ${e.cause?.message ?: e.message}"
            }
            if (status == 401) {
                Err(ServiceError.AuthenticationError(errorMsg))
            } else {
                Err(ServiceError.NetworkError(errorMsg, e))
            }
        }
    }

    private fun extractHttpStatus(e: Throwable): Int? {
        val msg = e.message ?: e.cause?.message ?: return null
        val match = Regex("""HTTP status code:? (\d{3})""", RegexOption.IGNORE_CASE).find(msg)
            ?: Regex("""status:? (\d{3})""", RegexOption.IGNORE_CASE).find(msg)
            ?: Regex("""\b(400|401|403|404|500|502|503)\b""").find(msg)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        suspend fun open(apiKey: String, lang: String?, model: String, host: String? = null): Result<LiveSpeechSession, ServiceError> {
            val session = DeepgramLiveSession()
            session.connect(apiKey, lang, model, host).getError()?.let { return Err(it) }
            return Ok(session)
        }

        suspend fun ping(apiKey: String, model: String, host: String? = null): Result<Unit, ServiceError> {
            val session = DeepgramLiveSession()
            val connectResult = session.connect(apiKey, "en", model, host)
            session.cancel()
            return connectResult
        }
    }
}

internal sealed interface DeepgramParsedMessage {
    data class Partial(val text: String, val words: List<RecognizedWord> = emptyList()) : DeepgramParsedMessage
    data class Final(val text: String, val words: List<RecognizedWord>) : DeepgramParsedMessage
    data class Error(val message: String) : DeepgramParsedMessage
    data object Ignored : DeepgramParsedMessage
}

private val deepgramJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseDeepgramMessage(text: String): DeepgramParsedMessage {
    val json = runCatching { deepgramJson.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return DeepgramParsedMessage.Ignored

    if (json.containsKey("error") || json["type"]?.jsonPrimitive?.contentOrNull == "Error") {
        val msg = json["description"]?.jsonPrimitive?.contentOrNull
            ?: json["message"]?.jsonPrimitive?.contentOrNull
            ?: text
        return DeepgramParsedMessage.Error(msg)
    }

    val channel = json["channel"]?.jsonObject ?: return DeepgramParsedMessage.Ignored
    val alternatives = channel["alternatives"]?.jsonArray ?: return DeepgramParsedMessage.Ignored
    val firstAlt = alternatives.firstOrNull()?.jsonObject ?: return DeepgramParsedMessage.Ignored

    val transcript = firstAlt["transcript"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (transcript.isEmpty()) return DeepgramParsedMessage.Ignored

    val isFinal = json["is_final"]?.jsonPrimitive?.booleanOrNull == true ||
        json["speech_final"]?.jsonPrimitive?.booleanOrNull == true

    val words = runCatching {
        firstAlt["words"]?.jsonArray?.mapNotNull { item ->
            val wordObj = item.jsonObject
            val w = wordObj["word"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val start = wordObj["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val end = wordObj["end"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val conf = wordObj["confidence"]?.jsonPrimitive?.doubleOrNull
            RecognizedWord(w, (start * 1000).toLong(), (end * 1000).toLong(), conf)
        }
    }.getOrNull().orEmpty()

    return if (isFinal) {
        DeepgramParsedMessage.Final(transcript, words)
    } else {
        DeepgramParsedMessage.Partial(transcript, words)
    }
}
