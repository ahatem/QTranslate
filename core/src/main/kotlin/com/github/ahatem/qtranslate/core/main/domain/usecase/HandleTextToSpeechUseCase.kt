package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.api.tts.TTSRequest
import com.github.ahatem.qtranslate.api.tts.TextToSpeech
import com.github.ahatem.qtranslate.core.audio.AudioPlayer
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputSource
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import kotlinx.coroutines.flow.StateFlow


class HandleTextToSpeechUseCase(
    private val activeServiceManager: ActiveServiceManager,
    private val settingsState: StateFlow<Configuration>,
    private val audioPlayer: AudioPlayer
) {

    suspend operator fun invoke(
        currentState: MainState,
        textSource: TextSource,
        textOverride: String?,
        onStatusUpdate: suspend (message: String, type: NotificationType) -> Unit
    ) {
        val textToSynthesize = textOverride ?: getTextFromSource(currentState, textSource)
        val language = determineLanguage(currentState, textSource)

        if (textToSynthesize.isBlank()) {
            onStatusUpdate("No text to listen to.", NotificationType.WARNING)
            return
        }
        if (language == null) {
            onStatusUpdate("Cannot determine the language for the selected text.", NotificationType.WARNING)
            return
        }

        val ttsService = activeServiceManager.getActiveService<TextToSpeech>(ServiceType.TTS, currentState)
        if (ttsService == null) {
            onStatusUpdate("No Text-to-Speech service is active.", NotificationType.WARNING)
            return
        }

        val supportedLanguages = ttsService.getSupportedLanguages().getOr(emptySet())
        if (language !in supportedLanguages) {
            onStatusUpdate(
                "The active TTS service, ${ttsService.name}, does not support the selected language.",
                NotificationType.WARNING
            )
            return
        }

        onStatusUpdate("Synthesizing speech with ${ttsService.name}...", NotificationType.INFO)

        val request = TTSRequest(text = textToSynthesize, language = language)
        ttsService.synthesize(request)
            .onSuccess { response ->
                when (val audio = response.audio) {
                    is TTSAudio.Bytes -> audioPlayer.play(audio.data)
                    is TTSAudio.StreamUrl -> {
                        onStatusUpdate(
                            "Streaming audio playback from a URL is not yet implemented.",
                            NotificationType.WARNING
                        )
                    }
                }
            }
            .onFailure { error ->
                onStatusUpdate("Text-to-Speech failed: ${error.message}", NotificationType.ERROR)
            }
    }

    private fun getTextFromSource(state: MainState, source: TextSource): String {
        return when (source) {
            TextSource.Input -> state.inputText
            TextSource.Output -> state.translatedText
            TextSource.ExtraOutput -> state.extraOutputText
        }
    }

    private fun determineLanguage(state: MainState, source: TextSource): LanguageCode? {
        return when (source) {
            TextSource.Input -> state.sourceLanguage
            TextSource.Output -> state.targetLanguage
            TextSource.ExtraOutput -> when (settingsState.value.extraOutputSource) {
                ExtraOutputSource.Input -> state.sourceLanguage
                ExtraOutputSource.Output -> state.targetLanguage
            }
        }
    }

    fun shutdown() {
        audioPlayer.close()
    }
}