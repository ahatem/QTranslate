package com.github.ahatem.qtranslate.plugins.lens

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.michaelbull.result.getOr
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LensLanguageMapperTest {

    @Test
    fun `supported languages include standard languages`() = runBlocking {
        val supported = LensLanguageMapper.getSupportedLanguages().getOr(emptySet())
        assertTrue(LanguageCode.ENGLISH in supported)
        assertTrue(LanguageCode.SPANISH in supported)
        assertTrue(LanguageCode.FRENCH in supported)
        assertTrue(LanguageCode.GERMAN in supported)
        assertTrue(LanguageCode.JAPANESE in supported)
        assertTrue(LanguageCode.CHINESE_SIMPLIFIED in supported)
        assertTrue(LanguageCode.CHINESE_TRADITIONAL in supported)
        assertTrue(LanguageCode.AUTO in supported)
    }

    @Test
    fun `outbound codes map provider-specific aliases`() {
        assertEquals("zh-CN", LensLanguageMapper.toProviderCode(LanguageCode.CHINESE_SIMPLIFIED))
        assertEquals("zh-TW", LensLanguageMapper.toProviderCode(LanguageCode.CHINESE_TRADITIONAL))
        assertEquals("iw", LensLanguageMapper.toProviderCode(LanguageCode.HEBREW))
        assertEquals("en", LensLanguageMapper.toProviderCode(LanguageCode.ENGLISH))
        assertEquals("es", LensLanguageMapper.toProviderCode(LanguageCode.SPANISH))
    }

    @Test
    fun `inbound codes map back to standard language codes`() {
        assertEquals(LanguageCode.CHINESE_SIMPLIFIED, LensLanguageMapper.fromProviderCode("zh-CN"))
        assertEquals(LanguageCode.CHINESE_TRADITIONAL, LensLanguageMapper.fromProviderCode("zh-TW"))
        assertEquals(LanguageCode.HEBREW, LensLanguageMapper.fromProviderCode("iw"))
        assertEquals(LanguageCode.ENGLISH, LensLanguageMapper.fromProviderCode("en"))
        assertEquals(LanguageCode.SPANISH, LensLanguageMapper.fromProviderCode("es"))
    }

    @Test
    fun `round-trip mapping preserves canonical languages`() {
        val canonicalLanguages = listOf(
            LanguageCode.ENGLISH,
            LanguageCode.SPANISH,
            LanguageCode.FRENCH,
            LanguageCode.GERMAN,
            LanguageCode.CHINESE_SIMPLIFIED,
            LanguageCode.CHINESE_TRADITIONAL,
            LanguageCode.HEBREW
        )
        for (lang in canonicalLanguages) {
            val providerCode = LensLanguageMapper.toProviderCode(lang)
            val mappedBack = LensLanguageMapper.fromProviderCode(providerCode)
            assertEquals(lang, mappedBack, "Round-trip failed for $lang (provider code: $providerCode)")
        }
    }

    @Test
    fun `unknown or regional provider code falls back gracefully`() {
        // Unknown regional dialect falls back to base language
        assertEquals(LanguageCode("pt"), LensLanguageMapper.fromProviderCode("pt-XYZ"))
        // Totally invalid code falls back safely to English without throwing
        val fallback = LensLanguageMapper.fromProviderCode("!!!invalid-tag???")
        assertEquals(LanguageCode("en"), fallback)
    }
}
