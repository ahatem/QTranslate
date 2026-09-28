package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.systemservices.spell.SpellingFinding
import com.github.ahatem.qtranslate.plugins.systemservices.spell.WindowsSpellCheckerBackend
import com.github.ahatem.qtranslate.plugins.systemservices.spell.WindowsSpellFailure
import com.github.ahatem.qtranslate.plugins.systemservices.spell.WindowsSpellNative
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WindowsSpellCheckerBackendTest {
    @Test fun `native work and release use one COM worker thread`() = runBlocking {
        val threads = mutableListOf<String>()
        val native = object : WindowsSpellNative {
            override fun languages(): Set<String> {
                threads += Thread.currentThread().name
                return setOf("en-US")
            }
            override fun check(text: String, languageTag: String): List<SpellingFinding> {
                threads += Thread.currentThread().name
                assertEquals("en-US", languageTag)
                return listOf(SpellingFinding(0, 3, listOf("the"), "teh"))
            }
            override fun close() { threads += Thread.currentThread().name }
        }
        val backend = WindowsSpellCheckerBackend(native)
        assertEquals(setOf("en-US"), backend.languages().unwrap())
        repeat(3) { assertEquals(1, backend.check("teh", "en-US").unwrap().size) }
        backend.close()
        assertEquals(5, threads.size)
        assertEquals(1, threads.distinct().size)
    }

    @Test fun `native failures do not become empty success or expose input`() = runBlocking {
        val native = object : WindowsSpellNative {
            override fun languages() = setOf("en-US")
            override fun check(text: String, languageTag: String): List<SpellingFinding> =
                throw WindowsSpellFailure("check", "0xdeadbeef")
        }
        val backend = WindowsSpellCheckerBackend(native)
        try {
            val error = backend.check("private misspelling", "en-US").unwrapError()
            assertIs<ServiceError.ServiceUnavailableError>(error)
            assertTrue("private misspelling" !in error.message)
        } finally { backend.close() }
    }
}
