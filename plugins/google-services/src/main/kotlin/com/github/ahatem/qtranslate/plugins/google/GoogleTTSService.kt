package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.tts.AudioFormat
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.api.tts.TTSRequest
import com.github.ahatem.qtranslate.api.tts.TTSResponse
import com.github.ahatem.qtranslate.api.tts.TextToSpeech
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.getOrElse

class GoogleTTSService(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig
) : TextToSpeech {

    override val id: String = "google-tts"
    override val name: String = "Google TTS"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/google-translate-icon.svg"

    // Google TTS supports a dynamic language set — same source as the translator.
    // The core calls fetchSupportedLanguages() once and caches the result.
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        languageMapper.getSupportedLanguages()

    companion object {
        private const val TTS_ENDPOINT = "https://translate.googleapis.com/translate_tts"
        private const val MAX_CHUNK_LENGTH = 200
    }

    override suspend fun synthesize(request: TTSRequest): Result<TTSResponse, ServiceError> =
        coroutineBinding {
            // TTSRequest is a sealed interface — resolve the language from whichever variant
            // the core dispatched. ByVoice carries the language on the Voice itself;
            // ByLanguage carries it directly. Google TTS is not a VoiceSupport service,
            // so the core will never send ByVoice here, but we handle it defensively.
            val language = when (request) {
                is TTSRequest.ByLanguage -> request.language
                is TTSRequest.ByVoice   -> request.voice.language
            }

            val langTag = languageMapper.toProviderCode(language)
            val speed = request.speed
            val chunks = partitionText(request.text)

            val audioData = tryPrimaryEndpoint(chunks, langTag, speed).getOrElse {
                pluginContext.logger.info("Primary TTS endpoint failed, trying fallback")
                tryFallbackEndpoint(chunks, langTag, speed).bind()
            }

            TTSResponse(audio = TTSAudio.Bytes(audioData, AudioFormat.MP3))
        }

    private suspend fun tryPrimaryEndpoint(
        chunks: List<String>,
        langTag: String,
        speed: Float
    ): Result<ByteArray, ServiceError> = coroutineBinding {
        val audioChunks = mutableListOf<ByteArray>()
        for ((idx, chunk) in chunks.withIndex()) {
            val bytes = httpClient.getBytes(
                url = TTS_ENDPOINT,
                headers = apiConfig.createHeaders(),
                queryParams = mapOf(
                    "client" to "gtx",
                    "ie" to "UTF-8",
                    "tl" to langTag,
                    "q" to chunk,
                    "total" to chunks.size,
                    "idx" to idx,
                    "textlen" to chunk.length,
                )
            ).bind()
            audioChunks.add(bytes)
        }
        audioChunks.reduce { acc, bytes -> acc + bytes }
    }

    private suspend fun tryFallbackEndpoint(
        chunks: List<String>,
        langTag: String,
        speed: Float
    ): Result<ByteArray, ServiceError> = coroutineBinding {
        val audioChunks = mutableListOf<ByteArray>()
        for (chunk in chunks) {
            val bytes = httpClient.getBytes(
                url = TTS_ENDPOINT,
                headers = apiConfig.createHeaders(),
                queryParams = mapOf(
                    "client" to "tw-ob",
                    "tl" to langTag,
                    "q" to chunk
                )
            ).bind()
            audioChunks.add(bytes)
        }
        audioChunks.reduce { acc, bytes -> acc + bytes }
    }

    private fun partitionText(text: String): List<String> =
        text.split("\\s+".toRegex())
            .fold(mutableListOf("")) { acc, word ->
                val current = acc.last()
                if (("$current $word").length > MAX_CHUNK_LENGTH) {
                    acc.add(word)
                } else {
                    acc[acc.lastIndex] = if (current.isEmpty()) word else "$current $word"
                }
                acc
            }
            .filter { it.isNotBlank() }
}