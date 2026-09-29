package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.spellchecker.CorrectionType
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.plugins.common.FakePluginContext
import com.github.ahatem.qtranslate.plugins.systemservices.spell.SpellingFinding
import com.github.ahatem.qtranslate.plugins.systemservices.spell.SystemSpellCheckerBackend
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SystemSpellCheckerServiceTest {
    private class FakeSpell(
        var findings: Result<List<SpellingFinding>, ServiceError> = Ok(emptyList()),
    ) : SystemSpellCheckerBackend {
        override val displayName = "Fake"
        var lastLanguage: String? = null
        override suspend fun languages(): Result<Set<String>, ServiceError> = Ok(setOf("en-US", "ar-EG"))
        override suspend fun check(text: String, languageTag: String): Result<List<SpellingFinding>, ServiceError> {
            lastLanguage = languageTag
            return findings
        }
    }

    private fun service(backend: FakeSpell = FakeSpell()) =
        SystemSpellCheckerService(backend, setOf("en-US", "ar-EG", "bad tag"), FakePluginContext().logger)

    @Test fun `languages preserve regions omit malformed tags and never advertise AUTO`() {
        val codes = (service().supportedLanguages as SupportedLanguages.Specific).languages
        assertTrue(LanguageCode("en-US") in codes)
        assertTrue(LanguageCode("ar-EG") in codes)
        assertTrue(LanguageCode.ENGLISH in codes)
        assertTrue(LanguageCode.AUTO !in codes)
        assertTrue(codes.none { it.tag.contains(' ') })
    }

    @Test fun `AUTO and unsupported language are rejected before native call`() = runBlocking {
        val backend = FakeSpell()
        val checker = service(backend)
        assertIs<ServiceError.UnsupportedLanguageError>(checker.check(SpellCheckRequest("text", LanguageCode.AUTO)).unwrapError())
        assertIs<ServiceError.UnsupportedLanguageError>(checker.check(SpellCheckRequest("text", LanguageCode.FRENCH)).unwrapError())
        assertEquals(null, backend.lastLanguage)
    }

    @Test fun `no findings preserve original text`() = runBlocking {
        val text = "A  sentence!"
        val response = service().check(SpellCheckRequest(text, LanguageCode.ENGLISH)).unwrap()
        assertEquals(text, response.correctedText)
        assertTrue(response.corrections.isEmpty())
    }

    @Test fun `repeated and adjacent findings retain their own source ranges and ranked suggestions`() = runBlocking {
        val backend = FakeSpell(Ok(listOf(
            SpellingFinding(0, 3, listOf("the", "The", "the"), "teh"),
            SpellingFinding(4, 3, listOf("the"), "teh"),
            SpellingFinding(7, 3, listOf("and"), "and"),
        )))
        val response = service(backend).check(SpellCheckRequest("teh tehand", LanguageCode.ENGLISH)).unwrap()
        assertEquals(listOf(0, 4, 7), response.corrections.map { it.startIndex })
        assertEquals(listOf("teh", "teh", "and"), response.corrections.map { it.original })
        assertEquals(listOf("the", "The"), response.corrections[0].suggestions)
        assertTrue(response.corrections.all { it.type == CorrectionType.SPELLING })
        assertEquals("the theand", response.correctedText)
        assertEquals("en-US", backend.lastLanguage)
    }

    @Test fun `emoji before finding uses UTF16 range`() = runBlocking {
        val backend = FakeSpell(Ok(listOf(SpellingFinding(3, 3, listOf("the"), "teh"))))
        val response = service(backend).check(SpellCheckRequest("😀 teh", LanguageCode.ENGLISH)).unwrap()
        assertEquals("teh", response.corrections.single().original)
        assertEquals("😀 the", response.correctedText)
    }

    @Test fun `Arabic finding keeps exact RTL span`() = runBlocking {
        val backend = FakeSpell(Ok(listOf(SpellingFinding(0, 4, listOf("مرحبا"), "مرحب"))))
        val response = service(backend).check(SpellCheckRequest("مرحب!", LanguageCode.ARABIC)).unwrap()
        assertEquals("مرحب", response.corrections.single().original)
        assertEquals("مرحبا!", response.correctedText)
        assertEquals("ar-EG", backend.lastLanguage)
    }

    @Test fun `finding without suggestions does not alter text`() = runBlocking {
        val backend = FakeSpell(Ok(listOf(SpellingFinding(0, 3, emptyList(), "teh"))))
        val response = service(backend).check(SpellCheckRequest("teh", LanguageCode.ENGLISH)).unwrap()
        assertEquals("teh", response.correctedText)
        assertEquals(emptyList(), response.corrections.single().suggestions)
    }

    @Test fun `invalid overlapping and mismatched ranges return errors`() = runBlocking {
        val backend = FakeSpell()
        val checker = service(backend)
        for (findings in listOf(
            listOf(SpellingFinding(-1, 2, emptyList())),
            listOf(SpellingFinding(2, 100, emptyList())),
            listOf(SpellingFinding(0, 2, emptyList(), "wrong")),
            listOf(SpellingFinding(0, 2, emptyList()), SpellingFinding(1, 2, emptyList())),
        )) {
            backend.findings = Ok(findings)
            assertIs<ServiceError.InvalidResponseError>(checker.check(SpellCheckRequest("teh", LanguageCode.ENGLISH)).unwrapError())
        }
    }

    @Test fun `backend failure stays a failure`() = runBlocking {
        val backend = FakeSpell(Err(ServiceError.ServiceUnavailableError("missing")))
        assertIs<ServiceError.ServiceUnavailableError>(
            service(backend).check(SpellCheckRequest("teh", LanguageCode.ENGLISH)).unwrapError()
        )
    }
}
