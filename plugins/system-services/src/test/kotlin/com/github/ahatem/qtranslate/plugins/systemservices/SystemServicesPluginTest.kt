package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.plugin.PluginSettings
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.tts.TextToSpeech
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.plugins.systemservices.spell.SpellingFinding
import com.github.ahatem.qtranslate.plugins.systemservices.spell.SystemSpellCheckerBackend
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

    private val spell = object : SystemSpellCheckerBackend {
        override val displayName = "Fake spelling"
        override suspend fun languages(): Result<Set<String>, ServiceError> = Ok(setOf("en-US"))
        override suspend fun check(text: String, languageTag: String): Result<List<SpellingFinding>, ServiceError> = Ok(emptyList())
    }

    private fun plugin(ocr: Boolean = true, speech: Boolean = true, spelling: Boolean = true) = SystemServicesPlugin(
        { if (ocr) Ok(FakeBackend()) else Err(ServiceError.ConfigurationError("OCR missing")) },
        { if (speech) Ok(tts) else Err(ServiceError.ConfigurationError("TTS missing")) },
        { if (spelling) Ok(spell) else Err(ServiceError.ServiceUnavailableError("Spell missing")) },
    )

    @Test fun `both capabilities are exposed with stable keys and roles`() = runBlocking {
        val plugin = plugin()
        assertEquals(emptyList(), plugin.getServices())
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-ocr", "system-tts", "system-spell-checker"), plugin.getServices().map { it.key })
        assertTrue(plugin.getServices()[0] is OCR)
        assertTrue(plugin.getServices()[1] is TextToSpeech)
        assertTrue(plugin.getServices()[2] is SpellChecker)
        assertTrue(plugin.getServices().all { it.metadata.requiresConfiguration == false && it.metadata.isFree == true })
    }

    @Test fun `OCR survives missing TTS`() = runBlocking {
        val plugin = plugin(speech = false)
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-ocr", "system-spell-checker"), plugin.getServices().map { it.key })
    }

    @Test fun `TTS survives missing OCR`() = runBlocking {
        val plugin = plugin(ocr = false)
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-tts", "system-spell-checker"), plugin.getServices().map { it.key })
    }

    @Test fun `zero capabilities still enables with no services`() = runBlocking {
        val plugin = plugin(ocr = false, speech = false, spelling = false)
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertTrue(plugin.getServices().isEmpty())
    }

    @Test fun `discovery exception does not hide sibling`() = runBlocking {
        val plugin = SystemServicesPlugin(
            { throw IllegalStateException("discovery failed") },
            { Ok(tts) },
            { Ok(spell) },
        )
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-tts", "system-spell-checker"), plugin.getServices().map { it.key })
    }

    @Test fun `disable shutdown and reenable rediscover without duplicates`() = runBlocking {
        var available = true
        var discoveries = 0
        val plugin = SystemServicesPlugin(
            { discoveries++; if (available) Ok(FakeBackend()) else Err(ServiceError.ConfigurationError("missing")) },
            { Ok(tts) },
            { Ok(spell) },
        )
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(3, plugin.getServices().size)
        plugin.onDisable()
        assertTrue(plugin.getServices().isEmpty())
        available = false
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-tts", "system-spell-checker"), plugin.getServices().map { it.key })
        plugin.onEnable().unwrap()
        assertEquals(2, plugin.getServices().size)
        assertEquals(3, discoveries)
        plugin.shutdown()
        assertTrue(plugin.getServices().isEmpty())
    }

    @Test fun `declares no settings`() {
        assertEquals(PluginSettings.None, plugin().getSettings())
    }

    @Test fun `OCR and TTS survive missing spelling`() = runBlocking {
        val plugin = plugin(spelling = false)
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-ocr", "system-tts"), plugin.getServices().map { it.key })
    }

    @Test fun `spelling works as the only capability`() = runBlocking {
        val plugin = plugin(ocr = false, speech = false)
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-spell-checker"), plugin.getServices().map { it.key })
    }

    @Test fun `spelling discovery exception does not hide siblings`() = runBlocking {
        val plugin = SystemServicesPlugin({ Ok(FakeBackend()) }, { Ok(tts) }, { throw IllegalStateException("missing") })
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-ocr", "system-tts"), plugin.getServices().map { it.key })
    }

    @Test fun `TTS discovery exception does not hide spelling`() = runBlocking {
        val plugin = SystemServicesPlugin({ Ok(FakeBackend()) }, { throw IllegalStateException("missing") }, { Ok(spell) })
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        assertEquals(listOf("system-ocr", "system-spell-checker"), plugin.getServices().map { it.key })
    }

    @Test fun `spell backend closes on rediscovery and disable`() = runBlocking {
        var closed = 0
        val tracked = object : SystemSpellCheckerBackend {
            override val displayName = "Tracked"
            override suspend fun languages(): Result<Set<String>, ServiceError> = Ok(setOf("en-US"))
            override suspend fun check(text: String, languageTag: String): Result<List<SpellingFinding>, ServiceError> = Ok(emptyList())
            override suspend fun close() { closed++ }
        }
        val plugin = SystemServicesPlugin({ Ok(FakeBackend()) }, { Ok(tts) }, { Ok(tracked) })
        plugin.initialize(FakePluginContext())
        plugin.onEnable().unwrap()
        plugin.onEnable().unwrap()
        assertEquals(1, closed)
        plugin.onDisable()
        assertEquals(2, closed)
        plugin.shutdown()
        assertEquals(2, closed)
    }
}
