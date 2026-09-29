package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceMetadata
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.tts.AudioFormat
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.api.tts.TTSRequest
import com.github.ahatem.qtranslate.api.tts.TTSResponse
import com.github.ahatem.qtranslate.api.tts.TextToSpeech
import com.github.ahatem.qtranslate.api.tts.Voice
import com.github.ahatem.qtranslate.api.tts.VoiceSupport
import com.github.ahatem.qtranslate.plugins.systemservices.tts.SystemTtsBackend
import com.github.ahatem.qtranslate.plugins.systemservices.tts.SystemVoice
import com.github.ahatem.qtranslate.plugins.systemservices.tts.VoiceLocaleMapper
import com.github.ahatem.qtranslate.plugins.systemservices.tts.TtsProcessSupport
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding

internal class SystemTtsService(
    private val backend: SystemTtsBackend,
    discovered: List<SystemVoice>,
    private val logger: Logger,
) : TextToSpeech, VoiceSupport {
    private val installed = discovered.distinctBy { it.id }.sortedWith(compareBy({ it.locale }, { it.id }))
    override val voices: List<Voice> = installed.mapNotNull { voice ->
        val codes = VoiceLocaleMapper.codes(voice.locale)
        (codes.firstOrNull { it.tag.contains('-') } ?: codes.firstOrNull())
            ?.let { Voice(voice.id, voice.name, it, voice.gender) }
    }
    override val key: String = "system-tts"
    override val name: String = "System TTS (Offline)"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/system-tts-icon.svg"
    override val metadata: ServiceMetadata = ServiceMetadata(
        requiresConfiguration = false,
        isFree = true,
        notes = DisplayText.literal("Local, offline speech using installed system voices. No account or API key required."),
    )
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Specific(
        installed.flatMap { VoiceLocaleMapper.codes(it.locale) }.toSet()
    )

    override suspend fun synthesize(request: TTSRequest): Result<TTSResponse, ServiceError> = coroutineBinding {
        val selected = when (request) {
            is TTSRequest.ByLanguage -> VoiceLocaleMapper.preferred(request.language, installed)
                ?: Err(ServiceError.UnsupportedLanguageError(
                    request.language, "No installed system voice supports '" + request.language.tag + "'."
                )).bind()
            is TTSRequest.ByVoice -> {
                val current = backend.discoverVoices().bind()
                current.firstOrNull { it.id == request.voice.id }
                    ?: Err(ServiceError.InvalidInputError("The selected system voice is no longer installed.")).bind()
            }
        }
        val started = System.nanoTime()
        val bytes = TtsProcessSupport.wav(backend.synthesize(request.text, selected.id, request.speed).bind()).bind()
        logger.info("System TTS (" + backend.displayName + ") synthesized " + bytes.size +
            " bytes for " + selected.locale + " in " + (System.nanoTime() - started) / 1_000_000 + "ms")
        TTSResponse(TTSAudio.Bytes(bytes, AudioFormat.WAV))
    }
}
