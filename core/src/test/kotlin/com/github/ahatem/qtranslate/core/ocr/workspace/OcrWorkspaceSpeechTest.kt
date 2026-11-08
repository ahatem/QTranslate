package com.github.ahatem.qtranslate.core.ocr.workspace

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.tts.AudioFormat
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.api.tts.TTSRequest
import com.github.ahatem.qtranslate.api.tts.TTSResponse
import com.github.ahatem.qtranslate.api.tts.TextToSpeech
import com.github.ahatem.qtranslate.core.audio.AudioPlayer
import com.github.ahatem.qtranslate.core.main.domain.usecase.HandleTextToSpeechUseCase
import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.main.mvi.Silent353Factory
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.TextSource
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Recognized-text TTS uses the OCR result's own provenance; translated-text TTS uses the
 * translation target. These are deliberately different sources of truth.
 */
class OcrWorkspaceSpeechTest {

    @Test
    fun `recognized text speaks its OCR provenance and translated text speaks the target`() = runBlocking {
        val tts = RecordingTts()
        val services = mapOf("tts" to tts)
        val useCase = HandleTextToSpeechUseCase(
            ActiveServiceManager(MutableStateFlow(services), MutableStateFlow(Configuration.DEFAULT)),
            MutableStateFlow(Configuration.DEFAULT),
            SilentPlayer,
            Silent353Factory,
        )
        val state = MainState(
            inputText = "こんにちは",
            translatedText = "Hello",
            sourceLanguage = LanguageCode.AUTO,
            detectedSourceLanguage = LanguageCode.JAPANESE,
            targetLanguage = LanguageCode.ENGLISH,
        )

        useCase.invoke(
            currentState = state,
            textSource = TextSource.Input,
            textOverride = "こんにちは",
            languageOverride = LanguageCode.JAPANESE,
            onStatusUpdate = { _, _, _ -> },
        )
        useCase.invoke(
            currentState = state,
            textSource = TextSource.Output,
            textOverride = "Hello",
            languageOverride = state.targetLanguage,
            onStatusUpdate = { _, _, _ -> },
        )

        assertEquals(
            LanguageCode.JAPANESE,
            (tts.requests[0] as TTSRequest.ByLanguage).language,
            "recognized text must not borrow an unrelated main-window language",
        )
        assertEquals(
            LanguageCode.ENGLISH,
            (tts.requests[1] as TTSRequest.ByLanguage).language,
            "translated text keeps using the translation target",
        )
    }

    @Test
    fun `unknown OCR provenance never borrows unrelated main English detection`() = runBlocking {
        val tts = RecordingTts()
        val configuration = MutableStateFlow(Configuration.DEFAULT)
        val useCase = HandleTextToSpeechUseCase(
            ActiveServiceManager(MutableStateFlow(mapOf("tts" to tts)), configuration),
            configuration, SilentPlayer, Silent353Factory,
        )
        val statuses = mutableListOf<com.github.ahatem.qtranslate.core.shared.StatusCode>()
        useCase(
            currentState = MainState(sourceLanguage = LanguageCode.AUTO, detectedSourceLanguage = LanguageCode.ENGLISH),
            textSource = TextSource.Input,
            textOverride = "synthetic OCR text with unknown provenance",
            languageOverride = LanguageCode.AUTO,
            onStatusUpdate = { code, _, _ -> statuses += code },
        )
        assertEquals(emptyList<TTSRequest>(), tts.requests)
        assertEquals(listOf<com.github.ahatem.qtranslate.core.shared.StatusCode>(com.github.ahatem.qtranslate.core.shared.StatusCode.CannotDetermineLanguage), statuses)
    }

    private class RecordingTts : TextToSpeech {
        val requests = mutableListOf<TTSRequest>()
        override val key = "tts"
        override val name = "Recording TTS"
        override val version = "test"
        override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

        override suspend fun synthesize(request: TTSRequest): Result<TTSResponse, ServiceError> {
            requests += request
            return Ok(TTSResponse(TTSAudio.Bytes(byteArrayOf(1), AudioFormat.MP3)))
        }
    }

    private object SilentPlayer : AudioPlayer {
        override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)
        override fun play(audio: TTSAudio.Bytes) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }
}
