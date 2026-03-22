package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.api.tts.TTSRequest
import com.github.ahatem.qtranslate.api.tts.TextToSpeech
import com.github.ahatem.qtranslate.core.audio.AudioPlayer
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

class HandleTextToSpeechUseCase(
    private val activeServiceManager: ActiveServiceManager,
    private val settingsState: StateFlow<Configuration>,
    private val audioPlayer: AudioPlayer,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("HandleTextToSpeechUseCase")

    suspend operator fun invoke(
        currentState: MainState,
        textSource: TextSource,
        textOverride: String?,
        onStatusUpdate: suspend (message: String, type: NotificationType, isTemporary: Boolean) -> Unit
    ) {
        val textToSynthesize = textOverride ?: getTextFromSource(currentState, textSource)
        val language = determineLanguage(currentState, textSource)

        if (textToSynthesize.isBlank()) {
            logger.debug("TTS skipped: text is blank")
            onStatusUpdate("No text to speak.", NotificationType.WARNING, true)
            return
        }

        if (language == null) {
            logger.warn("Cannot determine language for TTS")
            onStatusUpdate("Cannot determine the language for the selected text.", NotificationType.WARNING, true)
            return
        }

        val ttsService = activeServiceManager.getActiveService<TextToSpeech>(ServiceType.TTS)
        if (ttsService == null) {
            logger.warn("No TTS service available")
            onStatusUpdate("No Text-to-Speech service is active.", NotificationType.WARNING, true)
            return
        }

        val languageSupported = when (val supported = ttsService.supportedLanguages) {
            is SupportedLanguages.All -> true
            is SupportedLanguages.Dynamic -> true
            is SupportedLanguages.Specific -> language in supported.languages
        }

        if (!languageSupported) {
            logger.warn("TTS service '${ttsService.name}' does not support language: $language")
            onStatusUpdate(
                "The active TTS service (${ttsService.name}) does not support the selected language.",
                NotificationType.WARNING,
                true
            )
            return
        }

        logger.info("Starting TTS with '${ttsService.name}' for $textSource, language: $language")
        onStatusUpdate("Converting text to speech...", NotificationType.INFO, false)

        val request = TTSRequest.ByLanguage(text = textToSynthesize, language = language)

        val result = withTimeoutOrNull(AppConstants.TTS_TIMEOUT_MS) {
            ttsService.synthesize(request)
        }

        if (result == null) {
            logger.error("TTS timed out after ${AppConstants.TTS_TIMEOUT_MS}ms")
            onStatusUpdate("Text-to-speech timed out. Please try again.", NotificationType.ERROR, true)
            return
        }

        result
            .onOk { response ->
                when (val audio = response.audio) {
                    is TTSAudio.Bytes -> {
                        logger.info("TTS successful — playing ${audio.data.size} bytes (${audio.format})")
                        onStatusUpdate("Playing audio...", NotificationType.INFO, false)
                        audioPlayer.play(audio)
                        onStatusUpdate("Audio playback complete.", NotificationType.SUCCESS, true)
                    }

                    is TTSAudio.StreamUrl -> {
                        logger.warn("Streaming audio URLs are not yet supported")
                        onStatusUpdate(
                            "Streaming audio playback is not yet implemented.",
                            NotificationType.WARNING,
                            true
                        )
                    }
                }
            }
            .onErr { error ->
                logger.error("TTS failed: ${error.message}", error.cause)
                onStatusUpdate("Text-to-Speech failed: ${error.message}", NotificationType.ERROR, true)
            }
    }

    private fun getTextFromSource(state: MainState, source: TextSource): String =
        when (source) {
            TextSource.Input -> state.inputText
            TextSource.Output -> state.translatedText
            TextSource.ExtraOutput -> state.extraOutputText
        }

    private fun determineLanguage(state: MainState, source: TextSource): LanguageCode? =
        when (source) {
            TextSource.Input -> if (state.sourceLanguage == LanguageCode.AUTO) state.detectedSourceLanguage else state.sourceLanguage
            TextSource.Output -> state.targetLanguage
            TextSource.ExtraOutput -> when (settingsState.value.extraOutputSource) {
                ExtraOutputSource.Input -> if (state.sourceLanguage == LanguageCode.AUTO) state.detectedSourceLanguage else state.sourceLanguage
                ExtraOutputSource.Output -> state.targetLanguage
            }
        }

    fun shutdown() {
        logger.info("Shutting down TTS use case")
        runCatching { audioPlayer.close() }.onFailure { e ->
            logger.error("Error closing audio player during shutdown", e)
        }
    }
}