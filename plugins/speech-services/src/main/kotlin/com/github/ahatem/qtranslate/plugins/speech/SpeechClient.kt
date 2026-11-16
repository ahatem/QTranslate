package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.MultipartHttpClient
import com.github.ahatem.qtranslate.api.plugin.MultipartPart
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.speech.AudioEncoding
import com.github.ahatem.qtranslate.api.speech.RecognizedWord
import com.github.ahatem.qtranslate.api.speech.SpeechAudio
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionRequest
import com.github.ahatem.qtranslate.api.speech.SpeechRecognitionResponse
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.map
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.util.Base64
import java.nio.ByteOrder
import java.util.Locale

/** Talks to whichever provider the settings name. */
class SpeechClient(
    context: PluginContext,
    private val http: HttpClient,
    private val settings: () -> SpeechSettings
) {
    private val logger = context.logger
    private val openAiParser = createJsonParser<OpenAiTranscription>(context)
    private val deepgramParser = createJsonParser<DeepgramResponse>(context)
    private val chatParser = createJsonParser<ChatReply>(context)

    suspend fun transcribe(request: SpeechRecognitionRequest): Result<SpeechRecognitionResponse, ServiceError> {
        val current = settings()
        current.configurationError()?.let { return Err(it) }
        val uploads = http as? MultipartHttpClient
            ?: return Err(ServiceError.ConfigurationError("This version of QTranslate cannot upload audio. Update the app."))

        val language = primaryTag(request.language)
        return when (current.speechProvider.protocol) {
            SpeechProtocol.OPENAI_COMPATIBLE -> openAi(uploads, current, request, language)
            SpeechProtocol.DEEPGRAM -> deepgram(uploads, current, request.audio, language)
            SpeechProtocol.CHAT_AUDIO -> chatAudio(uploads, current, request, language)
        }.map { response ->
            response.copy(detectedLanguage = request.language.takeUnless { it == LanguageCode.AUTO } ?: response.detectedLanguage)
        }
    }

    /**
     * Checks the address, the key and, where the server lists its models, the model — without
     * transcribing anything. Sending a clip would be the fuller test, but a multimodal chat model
     * can take longer to answer than the settings dialog waits, and a slow answer is not a broken
     * setup. Whether the provider accepts audio shows on the first recorded line.
     */
    suspend fun testConnection(): Result<Unit, ServiceError> {
        val current = settings()
        if (current.speechProvider == SpeechProvider.NONE) return Ok(Unit)
        current.configurationError()?.let { return Err(it) }

        // Step A: Fast /models or /projects check
        val listingError = if (current.speechProvider.protocol == SpeechProtocol.DEEPGRAM) {
            http.get("${current.effectiveBaseUrl}/projects", authorization(current, "Token")).getError()
        } else {
            val listing = http.get("${current.effectiveBaseUrl}/models", authorization(current, "Bearer"))
            val err = listing.getError()
            if (err != null) {
                // Reachable without /models endpoint (e.g. self-hosted whisper servers)
                if (err is ServiceError.InvalidResponseError || err.message.contains("404")) null else err
            } else {
                val ids = listing.get()?.let { body -> runCatching { modelIds(body) }.getOrNull() }.orEmpty()
                if (ids.isNotEmpty() && current.effectiveModel !in ids) {
                    ServiceError.ConfigurationError(
                        SpeechLocalization.get("test_model_unknown", current.effectiveModel) +
                            " (Offers: ${ids.take(8).joinToString()}${if (ids.size > 8) ", ..." else ""})"
                    )
                } else null
            }
        }

        if (listingError != null) {
            return Err(classifyDiagnosticError(listingError, current.effectiveModel))
        }

        // Step B: Send 1-second silent clip to verify model accepts audio
        val uploads = http as? MultipartHttpClient
            ?: return Ok(Unit) // If uploads not available, listing check is best effort

        val silentAudio = SpeechAudio(silentWav(1000), AudioEncoding.WAV)
        val testRequest = SpeechRecognitionRequest(audio = silentAudio, language = LanguageCode("en"))

        val transcriptionResult = when (current.speechProvider.protocol) {
            SpeechProtocol.OPENAI_COMPATIBLE -> openAi(uploads, current, testRequest, "en")
            SpeechProtocol.DEEPGRAM -> deepgram(uploads, current, silentAudio, "en")
            SpeechProtocol.CHAT_AUDIO -> chatAudio(uploads, current, testRequest, "en")
        }

        val clipError = transcriptionResult.getError()
        if (clipError != null) {
            return Err(classifyDiagnosticError(clipError, current.effectiveModel))
        }

        return Ok(Unit)
    }

    private fun classifyDiagnosticError(error: ServiceError, model: String): ServiceError {
        val msg = error.message.lowercase()
        return when {
            error is ServiceError.AuthenticationError || msg.contains("401") || msg.contains("unauthorized") || msg.contains("invalid api key") ->
                ServiceError.AuthenticationError(SpeechLocalization.get("test_key_rejected"))
            msg.contains("model") && (msg.contains("not found") || msg.contains("does not exist") || msg.contains("unknown") || msg.contains("404")) ->
                ServiceError.ConfigurationError(SpeechLocalization.get("test_model_unknown", model))
            msg.contains("audio") || msg.contains("modality") || msg.contains("does not support") || msg.contains("invalid media") ->
                ServiceError.ConfigurationError(SpeechLocalization.get("test_model_not_audio", model))
            error is ServiceError.NetworkError || error is ServiceError.TimeoutError ->
                ServiceError.NetworkError(SpeechLocalization.get("test_server_unreachable", error.message))
            else -> error
        }
    }

    // -------------------------------------------------------------------------------------------
    // OpenAI-compatible
    // -------------------------------------------------------------------------------------------

    // Capability cache per (baseUrl, model) to remember if verbose_json was refused (P3)
    private val verboseSupportedCache = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Boolean>()

    private suspend fun openAi(
        uploads: MultipartHttpClient,
        settings: SpeechSettings,
        request: SpeechRecognitionRequest,
        language: String?
    ): Result<SpeechRecognitionResponse, ServiceError> {
        val cacheKey = Pair(settings.effectiveBaseUrl, settings.effectiveModel)
        val supportsVerbose = verboseSupportedCache[cacheKey] ?: true

        if (!supportsVerbose) {
            return openAiRequest(uploads, settings, request, language, verbose = false)
        }

        val verbose = openAiRequest(uploads, settings, request, language, verbose = true)
        val error = verbose.getError() ?: return verbose
        // Retry in plain JSON only for a "format not supported" 400 error, not for auth, not found, or server errors
        val isFormatUnsupported = error is ServiceError.InvalidResponseError ||
            (error is ServiceError.ValidationError && (error.message.contains("format", ignoreCase = true) || error.message.contains("verbose_json", ignoreCase = true))) ||
            error.message.contains("400") && (error.message.contains("format", ignoreCase = true) || error.message.contains("response_format", ignoreCase = true) || error.message.contains("verbose", ignoreCase = true))

        if (isFormatUnsupported) {
            verboseSupportedCache[cacheKey] = false
            logger.debug("Verbose transcription refused (${error.message}); cached and retrying in plain JSON")
            return openAiRequest(uploads, settings, request, language, verbose = false)
        }
        return verbose
    }

    private suspend fun openAiRequest(
        uploads: MultipartHttpClient,
        settings: SpeechSettings,
        request: SpeechRecognitionRequest,
        language: String?,
        verbose: Boolean
    ): Result<SpeechRecognitionResponse, ServiceError> {
        val parts = buildList {
            add(MultipartPart.File("file", "speech.${request.audio.encoding.extension}", request.audio.encoding.mimeType, request.audio.bytes))
            add(MultipartPart.Text("model", settings.effectiveModel))
            add(MultipartPart.Text("temperature", "0"))
            if (verbose) {
                add(MultipartPart.Text("response_format", "verbose_json"))
                add(MultipartPart.Text("timestamp_granularities[]", "word"))
            } else {
                add(MultipartPart.Text("response_format", "json"))
            }
            language?.let { add(MultipartPart.Text("language", it)) }
            // Whisper reads only the last 224 tokens of a prompt; the tail is what matters.
            request.prompt?.takeIf { it.isNotBlank() }?.let { add(MultipartPart.Text("prompt", it.takeLast(MAX_PROMPT_CHARS))) }
        }
        return uploads.postMultipart(
            url = "${settings.effectiveBaseUrl}/audio/transcriptions",
            parts = parts,
            headers = authorization(settings, "Bearer")
        ).andThen { openAiParser.parse(it) }.andThen { body ->
            body.error?.let { return@andThen Err(ServiceError.InvalidResponseError(it.message)) }
            Ok(body.toResponse())
        }
    }

    // -------------------------------------------------------------------------------------------
    // Deepgram
    // -------------------------------------------------------------------------------------------

    private suspend fun deepgram(
        uploads: MultipartHttpClient,
        settings: SpeechSettings,
        audio: SpeechAudio,
        language: String?
    ): Result<SpeechRecognitionResponse, ServiceError> {
        val query = buildMap<String, Any?> {
            put("model", settings.effectiveModel)
            put("smart_format", "true")
            put("punctuate", "true")
            if (language != null) put("language", language) else put("detect_language", "true")
        }
        return uploads.postBytes(
            url = "${settings.effectiveBaseUrl}/listen",
            body = audio.bytes,
            contentType = audio.encoding.mimeType,
            headers = authorization(settings, "Token"),
            queryParams = query
        ).andThen { deepgramParser.parse(it) }.map { it.toResponse() }
    }

    // -------------------------------------------------------------------------------------------
    // Multimodal chat model
    // -------------------------------------------------------------------------------------------

    private suspend fun chatAudio(
        uploads: MultipartHttpClient,
        settings: SpeechSettings,
        request: SpeechRecognitionRequest,
        language: String?
    ): Result<SpeechRecognitionResponse, ServiceError> {
        val instruction = buildString {
            append("Transcribe the speech in this audio exactly as spoken, in the language it is spoken in. ")
            append("Do not translate, summarise or explain. Reply with the transcript only, without quotes. ")
            append("If there is no speech, reply with nothing.")
            language?.let { append(" The speaker is expected to speak language code '$it'.") }
            request.prompt?.takeIf { it.isNotBlank() }?.let {
                append(" For spelling of names and terms, the conversation so far was: ")
                append(it.takeLast(MAX_PROMPT_CHARS))
            }
        }
        val body = buildJsonObject {
            put("model", settings.effectiveModel)
            put("temperature", 0)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", instruction) }
                        addJsonObject {
                            put("type", "input_audio")
                            putJsonObject("input_audio") {
                                put("data", Base64.getEncoder().encodeToString(request.audio.bytes))
                                put("format", request.audio.encoding.extension)
                            }
                        }
                    }
                }
            }
        }
        // Sent as an upload rather than a plain post: the model has to listen to the clip before it
        // answers, which takes longer than an ordinary request is allowed.
        return uploads.postBytes(
            url = "${settings.effectiveBaseUrl}/chat/completions",
            body = body.toString().toByteArray(Charsets.UTF_8),
            contentType = "application/json",
            headers = authorization(settings, "Bearer")
        ).andThen { chatParser.parse(it) }.andThen { reply ->
            reply.error?.let { return@andThen Err(ServiceError.InvalidResponseError(it.message)) }
            var text = reply.choices.firstOrNull()?.message?.content.orEmpty().trim()

            // P13: Strip markdown code fences if wrapped in ``` ... ```
            if (text.startsWith("```") && text.endsWith("```")) {
                text = text.removeSurrounding("```").substringAfter('\n').substringBeforeLast('\n').trim()
            }

            // Strip common conversational preamble labels
            val prefixes = listOf(
                "transcript:", "transcription:", "audio transcript:",
                "here is the transcript:", "the transcript is:"
            )
            for (prefix in prefixes) {
                if (text.startsWith(prefix, ignoreCase = true)) {
                    text = text.substring(prefix.length).trim()
                    break
                }
            }

            // Strip surrounding quotes
            text = text.trim('"', '“', '”', '\'', '`').trim()

            // Refuse obvious non-transcripts specifically about transcription refusal / failure,
            // without rejecting spoken utterances like "I can't hear you" or "as an AI researcher"
            val nonTranscriptPatterns = listOf(
                "i cannot transcribe", "i can't transcribe", "i am unable to transcribe",
                "i'm unable to transcribe", "cannot transcribe audio", "unable to transcribe audio",
                "cannot process audio", "unable to process audio"
            )
            if (nonTranscriptPatterns.any { text.startsWith(it, ignoreCase = true) }) {
                return@andThen Err(ServiceError.ValidationError("Model returned an explanation instead of a transcript: '$text'"))
            }

            Ok(SpeechRecognitionResponse(text = text))
        }
    }

    private fun authorization(settings: SpeechSettings, scheme: String): Map<String, String> =
        settings.apiKey.trim().takeIf { it.isNotEmpty() }?.let { mapOf("Authorization" to "$scheme $it") }.orEmpty()

    internal companion object {
        const val MAX_PROMPT_CHARS = 600

        /** The ISO-639-1 code providers take, or null to let them detect the language. */
        fun primaryTag(language: LanguageCode): String? =
            language.takeUnless { it == LanguageCode.AUTO }
                ?.tag
                ?.substringBefore('-')
                ?.lowercase()
                ?.takeIf { it.length in 2..3 }

        /**
         * Whisper's verbose format names the language in English ("english", "arabic") rather
         * than coding it. Matched against the JDK's own English language names.
         */
        fun languageFromName(name: String?): LanguageCode? {
            val wanted = name?.trim()?.lowercase().orEmpty()
            if (wanted.isEmpty()) return null
            if (wanted.length in 2..3) return LanguageCode(wanted)
            return Locale.getISOLanguages()
                .firstOrNull { Locale(it).getDisplayLanguage(Locale.ENGLISH).lowercase() == wanted }
                ?.let(::LanguageCode)
        }

        /** Model ids from an OpenAI-style `/models` listing. */
        fun modelIds(body: String): List<String> =
            listJson.decodeFromString(ModelList.serializer(), body).data.map { it.id }

        private val listJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

        fun silentWav(millis: Int, sampleRate: Int = 16_000): ByteArray {
            val dataBytes = sampleRate * millis / 1000 * 2
            return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(dataBytes)
            }.array()
        }

        internal fun OpenAiTranscription.toResponse() = SpeechRecognitionResponse(
            text = text.orEmpty().trim(),
            detectedLanguage = languageFromName(language),
            words = words.orEmpty().toRecognized { Triple(it.word, it.start, it.end) }
        )

        internal fun DeepgramResponse.toResponse(): SpeechRecognitionResponse {
            val channel = results?.channels?.firstOrNull()
            val best = channel?.alternatives?.firstOrNull()
            return SpeechRecognitionResponse(
                text = best?.transcript.orEmpty().trim(),
                detectedLanguage = channel?.detectedLanguage?.takeIf { it.isNotBlank() }?.let(::LanguageCode),
                words = best?.words.orEmpty().mapNotNull { w ->
                    val text = w.punctuatedWord ?: w.word
                    if (text.isBlank()) return@mapNotNull null
                    val startMillis = (w.start * 1000).toLong().coerceAtLeast(0)
                    val endMillis = (w.end * 1000).toLong().coerceAtLeast(startMillis)
                    RecognizedWord(text.trim(), startMillis, endMillis, w.confidence)
                }
            )
        }

        private fun <T> List<T>.toRecognized(fields: (T) -> Triple<String, Double, Double>): List<RecognizedWord> =
            mapNotNull { item ->
                val (word, start, end) = fields(item)
                if (word.isBlank()) return@mapNotNull null
                val startMillis = (start * 1000).toLong().coerceAtLeast(0)
                RecognizedWord(word.trim(), startMillis, (end * 1000).toLong().coerceAtLeast(startMillis))
            }
    }
}
