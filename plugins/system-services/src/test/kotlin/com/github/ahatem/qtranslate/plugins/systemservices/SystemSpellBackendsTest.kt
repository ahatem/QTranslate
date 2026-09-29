package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ExecutableLocator
import com.github.ahatem.qtranslate.plugins.systemservices.backend.ProcessOutcome
import com.github.ahatem.qtranslate.plugins.systemservices.spell.LinuxSpellCheckerBackend
import com.github.ahatem.qtranslate.plugins.systemservices.spell.MacSpellCheckerBackend
import com.github.michaelbull.result.Err
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SystemSpellBackendsTest {
    @Test fun `Linux chooses Enchant before Hunspell and never puts source text in arguments`() = runBlocking {
        val runner = FakeProcessRunner { command ->
            when {
                "-list-dicts" in command -> ProcessOutcome(0, "en_US (hunspell)\nar_EG (hunspell)\n", "", false)
                else -> ProcessOutcome(0, "@(#) Enchant\n\n& smple 1 11: sample\n\n", "", false)
            }
        }
        val locator = ExecutableLocator { name -> when (name) {
            "enchant-2", "enchant-lsmod-2", "hunspell" -> name
            else -> null
        } }
        val backend = LinuxSpellCheckerBackend.discover(locator, runner).unwrap()
        assertEquals("ENCHANT", backend.displayName)
        assertEquals(setOf("en_US", "ar_EG"), backend.languages().unwrap())
        backend.check("This is a smple", "en_US").unwrap()
        assertTrue(runner.commands.last().none { "This is a smple" in it })
    }

    @Test fun `Linux falls back to Hunspell and none is harmless`() = runBlocking {
        val runner = FakeProcessRunner { ProcessOutcome(0, "", "AVAILABLE DICTIONARIES:\n/usr/share/hunspell/en_US\n", false) }
        val locator = ExecutableLocator { if (it == "hunspell") "hunspell" else null }
        assertEquals(setOf("en_US"), LinuxSpellCheckerBackend.discover(locator, runner).unwrap().languages().unwrap())
        assertIs<ServiceError.ServiceUnavailableError>(
            LinuxSpellCheckerBackend.discover(ExecutableLocator { null }, runner).unwrapError()
        )
        Unit
    }

    @Test fun `Linux Ispell output preserves repeated UTF16 source positions`() {
        val output = "@(#) Hunspell\n\n& teh 1 1: the\n& teh 1 5: the\n\n"
        val findings = LinuxSpellCheckerBackend.parse("teh teh", output).unwrap()
        assertEquals(listOf(0, 4), findings.map { it.start })
        assertEquals(listOf("the", "the"), findings.map { it.suggestions.single() })
    }

    @Test fun `Linux maps emoji preceding finding and rejects mismatched offsets`() {
        val output = "@(#) Hunspell\n\n& teh 1 4: the\n\n"
        assertEquals(3, LinuxSpellCheckerBackend.parse("😀 teh", output).unwrap().single().start)
        assertIs<ServiceError.InvalidResponseError>(
            LinuxSpellCheckerBackend.parse("😀 teh", "@(#) Hunspell\n\n& teh 1 2: the\n\n").unwrapError()
        )
    }

    @Test fun `macOS helper parses Unicode ranges and rejects malformed output`() = runBlocking {
        val helper = File.createTempFile("system-spell-helper", "").apply { setExecutable(true) }
        try {
            val runner = helperWriting("""{"ok":true,"findings":[{"start":3,"length":3,"original":"teh","suggestions":["the","ten"]}]}""", "--output")
            val findings = MacSpellCheckerBackend(runner, helper).check("😀 teh", "en-US").unwrap()
            assertEquals(3, findings.single().start)
            assertEquals(listOf("the", "ten"), findings.single().suggestions)
            assertTrue(runner.commands.single().none { "😀 teh" in it })
            val bad = MacSpellCheckerBackend(helperWriting("{}", "--output"), helper)
            assertIs<ServiceError.InvalidResponseError>(bad.languages().unwrapError())
        } finally { helper.delete() }
        Unit
    }
}
