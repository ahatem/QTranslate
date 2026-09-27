package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.tts.AudioFormat
import com.github.ahatem.qtranslate.api.tts.TTSAudio
import com.github.ahatem.qtranslate.api.tts.TTSRequest
import com.github.ahatem.qtranslate.api.tts.Voice
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.systemservices.tts.SystemTtsBackend
import com.github.ahatem.qtranslate.plugins.systemservices.tts.SystemVoice
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal fun wav(): ByteArray = ByteArray(48).also { bytes ->
    "RIFF".toByteArray().copyInto(bytes, 0)
    bytes[4] = 40
    "WAVEfmt ".toByteArray().copyInto(bytes, 8)
    bytes[16] = 16; bytes[20] = 1; bytes[22] = 1
    bytes[24] = 0x80.toByte(); bytes[25] = 0x3e
    bytes[28] = 0x00; bytes[29] = 0x7d
    bytes[32] = 2; bytes[34] = 16
    "data".toByteArray().copyInto(bytes, 36)
    bytes[40] = 4
}

class SystemTtsServiceTest {
    private val english = SystemVoice("en-id", "English", "en-US")
    private val arabic = SystemVoice("ar-id", "Arabic", "ar-EG")

    private class FakeSpeech(var available: List<SystemVoice>) : SystemTtsBackend {
        override val displayName = "fake"
        var selected: String? = null
        var result: Result<ByteArray, ServiceError> = Ok(wav())
        override suspend fun discoverVoices(): Result<List<SystemVoice>, ServiceError> = Ok(available)
        override suspend fun synthesize(text: String, voiceId: String, speed: Float): Result<ByteArray, ServiceError> {
            selected = voiceId
            return result
        }
    }

    @Test fun `language chooses installed voice and returns WAV`() = runBlocking {
        val backend = FakeSpeech(listOf(arabic, english))
        val service = SystemTtsService(backend, backend.available, FakePluginContext.SilentLogger)
        val audio = service.synthesize(TTSRequest.ByLanguage("Hello", LanguageCode.ENGLISH)).unwrap().audio
        assertEquals("en-id", backend.selected)
        assertIs<TTSAudio.Bytes>(audio)
        assertEquals(AudioFormat.WAV, audio.format)
        assertTrue(audio.data.isNotEmpty())
        assertEquals(setOf(LanguageCode("en-US"), LanguageCode.ENGLISH, LanguageCode("ar-EG"), LanguageCode.ARABIC),
            (service.supportedLanguages as SupportedLanguages.Specific).languages)
    }

    @Test fun `unsupported language is reported`() = runBlocking {
        val backend = FakeSpeech(listOf(english))
        val service = SystemTtsService(backend, backend.available, FakePluginContext.SilentLogger)
        assertIs<ServiceError.UnsupportedLanguageError>(
            service.synthesize(TTSRequest.ByLanguage("Hallo", LanguageCode.GERMAN)).unwrapError())
    }

    @Test fun `explicit voice uses exact id and missing voice is rejected`() = runBlocking {
        val backend = FakeSpeech(listOf(english, arabic))
        val service = SystemTtsService(backend, backend.available, FakePluginContext.SilentLogger)
        service.synthesize(TTSRequest.ByVoice("مرحبا", Voice("ar-id", "Arabic", LanguageCode.ARABIC))).unwrap()
        assertEquals("ar-id", backend.selected)
        backend.available = listOf(english)
        assertIs<ServiceError.InvalidInputError>(
            service.synthesize(TTSRequest.ByVoice("مرحبا", Voice("ar-id", "Arabic", LanguageCode.ARABIC))).unwrapError())
        assertEquals("ar-id", backend.selected)
    }

    @Test fun `backend error and malformed audio are errors`() = runBlocking {
        val backend = FakeSpeech(listOf(english))
        val service = SystemTtsService(backend, backend.available, FakePluginContext.SilentLogger)
        backend.result = Err(ServiceError.ServiceUnavailableError("engine failed"))
        assertIs<ServiceError.ServiceUnavailableError>(
            service.synthesize(TTSRequest.ByLanguage("private text", LanguageCode.ENGLISH)).unwrapError())
        backend.result = Ok(byteArrayOf(1, 2))
        assertIs<ServiceError.InvalidResponseError>(
            service.synthesize(TTSRequest.ByLanguage("private text", LanguageCode.ENGLISH)).unwrapError())
    }

    @Test fun `voice discovery is sorted and deduplicated`() {
        val backend = FakeSpeech(listOf(english, arabic, english))
        val service = SystemTtsService(backend, backend.available, FakePluginContext.SilentLogger)
        assertEquals(listOf("ar-id", "en-id"), service.voices.map { it.id })
    }
}
