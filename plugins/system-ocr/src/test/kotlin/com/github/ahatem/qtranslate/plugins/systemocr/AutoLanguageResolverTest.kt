package com.github.ahatem.qtranslate.plugins.systemocr

import com.github.ahatem.qtranslate.api.language.LanguageCode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoLanguageResolverTest {

    @Test
    fun `the platform language is used when the engine has it`() {
        val supported = setOf(LanguageCode.ENGLISH, LanguageCode.GERMAN, LanguageCode("en-US"))

        assertEquals(LanguageCode("en-US"), AutoLanguageResolver.resolve(supported, listOf("en-US")))
    }

    @Test
    fun `a language sharing the platform primary subtag is used next`() {
        val supported = setOf(LanguageCode("en-GB"), LanguageCode("de-DE"))

        assertEquals(LanguageCode("en-GB"), AutoLanguageResolver.resolve(supported, listOf("en-US")))
    }

    @Test
    fun `a platform region matches a bare installed language`() {
        val supported = setOf(LanguageCode.ENGLISH, LanguageCode.GERMAN)

        assertEquals(LanguageCode.ENGLISH, AutoLanguageResolver.resolve(supported, listOf("en-GB")))
    }

    @Test
    fun `an unrelated platform language falls back to an installed one`() {
        val supported = setOf(LanguageCode("ar-SA"), LanguageCode("en-US"))

        assertEquals(LanguageCode("ar-SA"), AutoLanguageResolver.resolve(supported, listOf("fr-FR")))
    }

    @Test
    fun `the installed-language fallback is deterministic`() {
        val supported = setOf(LanguageCode("ja-JP"), LanguageCode("ar-SA"), LanguageCode("en-US"))

        repeat(5) {
            assertEquals(LanguageCode("ar-SA"), AutoLanguageResolver.resolve(supported, listOf("fr-FR")))
        }
    }

    @Test
    fun `a later platform language is used when the first is not installed`() {
        val supported = setOf(LanguageCode("ja-JP"))

        assertEquals(LanguageCode("ja-JP"), AutoLanguageResolver.resolve(supported, listOf("fr-FR", "ja-JP")))
    }

    @Test
    fun `an unusable platform tag is ignored`() {
        val supported = setOf(LanguageCode.ENGLISH)

        assertEquals(LanguageCode.ENGLISH, AutoLanguageResolver.resolve(supported, listOf("", "!?", "en")))
    }

    @Test
    fun `nothing installed resolves to nothing`() {
        assertNull(AutoLanguageResolver.resolve(emptySet(), listOf("en-US")))
    }

    @Test
    fun `the platform preference is the default locale`() {
        assertEquals(listOf(Locale.getDefault().toLanguageTag()), AutoLanguageResolver.platformPreferredTags())
    }
}
