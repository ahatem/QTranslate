package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.tts.TextToSpeech
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.systemservices.tts.SystemTtsBackend
import com.github.ahatem.qtranslate.plugins.systemservices.tts.SystemVoice
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemServicesPluginTest {
    private val voice = SystemVoice("english", "English", "en-US")
    private val tts = object : SystemTtsBackend {
        override val displayName = "Fake speech"
        override suspend fun discoverVoices(): Result<List<SystemVoice>, ServiceError> = Ok(listOf(voice))
        override suspend fun synthesize(text: String, voiceId: String, speed: Float): Result<ByteArray, ServiceError> =
            Ok(wav())
    }

    private fun plugin(ocr: Boolean = true, speech: Boolean = true) = SystemServicesPlugin(
        { if (ocr) Ok(FakeBackend()) else Err(ServiceError.ConfigurationError("OCR missing")) },
        { if (speech) Ok(tts) else Err(ServiceError.ConfigurationError("TTS missing")) },
    )

    @Test fun `both capabilities are exposed with stable keys and roles`() = runBlocking {
        val plugin = plugin()
        assertEquals(emptyList(), plugin.getServices())
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-ocr", "system-tts"), plugin.getServices().map { it.key })
        assertTrue(plugin.getServices()[0] is OCR)
        assertTrue(plugin.getServices()[1] is TextToSpeech)
        assertTrue(plugin.getServices().all { it.metadata.requiresConfiguration == false && it.metadata.isFree == true })
    }

    @Test fun `OCR survives missing TTS`() = runBlocking {
        val plugin = plugin(speech = false)
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-ocr"), plugin.getServices().map { it.key })
    }

    @Test fun `TTS survives missing OCR`() = runBlocking {
        val plugin = plugin(ocr = false)
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-tts"), plugin.getServices().map { it.key })
    }

    @Test fun `zero capabilities still enables with no services`() = runBlocking {
        val plugin = plugin(ocr = false, speech = false)
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertTrue(plugin.getServices().isEmpty())
    }

    @Test fun `discovery exception does not hide sibling`() = runBlocking {
        val plugin = SystemServicesPlugin(
            { throw IllegalStateException("discovery failed") },
            { Ok(tts) },
        )
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-tts"), plugin.getServices().map { it.key })
    }

    @Test fun `disable shutdown and reenable rediscover without duplicates`() = runBlocking {
        var available = true
        var discoveries = 0
        val plugin = SystemServicesPlugin(
            { discoveries++; if (available) Ok(FakeBackend()) else Err(ServiceError.ConfigurationError("missing")) },
            { Ok(tts) },
        )
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(2, plugin.getServices().size)
        plugin.onDisable()
        assertTrue(plugin.getServices().isEmpty())
        available = false
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-tts"), plugin.getServices().map { it.key })
        plugin.onEnable().unwrap()
        assertEquals(1, plugin.getServices().size)
        assertEquals(3, discoveries)
        plugin.shutdown()
        assertTrue(plugin.getServices().isEmpty())
    }

    @Test fun `declares no settings`() {
        assertEquals(PluginSettings.None, plugin().getSettings())
    }
}
