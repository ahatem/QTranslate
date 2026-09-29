package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.systemservices.spell.WindowsSpellCheckerBackend
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsSpellCheckerSmokeTest {
    @Test fun `real Windows spelling preserves ranges and suggestions`() = runBlocking {
        if (System.getenv("SYSTEM_SPELL_SMOKE") != "true" || !System.getProperty("os.name").startsWith("Windows")) return@runBlocking
        val backend = WindowsSpellCheckerBackend()
        try {
        val discoveryStart = System.nanoTime()
        val languages = backend.languages().unwrap()
        val discoveryMs = (System.nanoTime() - discoveryStart) / 1_000_000.0
        println("Windows spell languages: ${languages.sorted()}; discovery: ${discoveryMs}ms")
        val english = languages.firstOrNull { it.startsWith("en", ignoreCase = true) } ?: return@runBlocking
        val service = SystemSpellCheckerService(backend, languages, FakePluginContext().logger)
        val firstStart = System.nanoTime()
        val first = service.check(SpellCheckRequest("Ths is a smple sentnce.", LanguageCode(english))).unwrap()
        val firstMs = (System.nanoTime() - firstStart) / 1_000_000.0
        val secondStart = System.nanoTime()
        val second = service.check(SpellCheckRequest("😀 This is a smple.", LanguageCode(english))).unwrap()
        val secondMs = (System.nanoTime() - secondStart) / 1_000_000.0
        println("Windows spell check first: ${firstMs}ms; repeat: ${secondMs}ms; findings: ${first.corrections.size}")
        assertTrue(first.corrections.isNotEmpty())
        assertTrue(first.corrections.any { it.suggestions.isNotEmpty() })
        assertEquals("smple", second.corrections.first { it.original == "smple" }.original)
        assertEquals(13, second.corrections.first { it.original == "smple" }.startIndex)
        val repeated = service.check(SpellCheckRequest("smple smple", LanguageCode(english))).unwrap()
        assertEquals(listOf(0, 6), repeated.corrections.filter { it.original == "smple" }.map { it.startIndex })
        if ("ar-EG" in languages) {
            val arabic = service.check(SpellCheckRequest("مرحباا", LanguageCode("ar-EG"))).unwrap()
            println("Windows Arabic spell findings: ${arabic.corrections.size}")
            assertTrue(arabic.corrections.all { "مرحباا".substring(it.startIndex, it.endIndex) == it.original })
        }
        } finally { backend.close() }
    }
}
