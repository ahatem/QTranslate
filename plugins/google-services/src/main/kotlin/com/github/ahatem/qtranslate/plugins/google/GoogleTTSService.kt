package com.github.ahatem.qtranslate.plugins.google


import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.tts.AudioFormat
import com.github.ahatem.qtranslate.api.tts.Gender
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.api.tts.TTSRequest
import com.github.ahatem.qtranslate.api.tts.TTSResponse
import com.github.ahatem.qtranslate.api.tts.TextToSpeech
import com.github.ahatem.qtranslate.api.tts.Voice
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.toResultOr

class GoogleTTSService(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig
) : TextToSpeech {

    override val id: String = "google-services-tts"
    override val name: String = "Google TTS"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/google-translate-icon.svg"

    companion object {
        private const val TTS_ENDPOINT = "https://translate.googleapis.com/translate_tts"
        private const val MAX_CHUNK_LENGTH = 200
    }

    override suspend fun getSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> {
        return languageMapper.getSupportedLanguages()
    }

    override suspend fun synthesize(request: TTSRequest): Result<TTSResponse, ServiceError> = coroutineBinding {
        val voice = request.voice
            .toResultOr { ServiceError.InvalidInputError("Voice must be specified for TTS") }
            .getOr(
                Voice(
                    id = "default",
                    name = "Default",
                    language = LanguageCode.ENGLISH,
                    gender = Gender.FEMALE
                )
            )

        val langTag = languageMapper.toProviderCode(voice.language)
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
//                    "ttsspeed" to speed
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

    private fun partitionText(text: String): List<String> {
        return text.split("\\s+".toRegex())
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
}