package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.michaelbull.result.getOr
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleLanguageMapperTest {

    @Test
    fun `supported set advertises regional Portuguese without the generic duplicate`() = runBlocking {
        val supported = GoogleLanguageMapper.getSupportedLanguages().getOr(emptySet())
        assertTrue(LanguageCode.PORTUGUESE_BRAZIL in supported)
        assertTrue(LanguageCode.PORTUGUESE_PORTUGAL in supported)
        assertFalse(LanguageCode.PORTUGUESE in supported)
    }

    @Test
    fun `codes map to the provider and back`() = runBlocking {
        val supported = GoogleLanguageMapper.getSupportedLanguages().getOr(emptySet())
        // (language, provider code) pairs that round-trip.
        val pairs = listOf(
            LanguageCode.PORTUGUESE_BRAZIL to "pt-BR",
            LanguageCode.PORTUGUESE_PORTUGAL to "pt-PT",
            LanguageCode.GUARANI to "gn",
            LanguageCode.GEORGIAN to "ka",
        )
        for ((language, code) in pairs) {
            assertEquals(code, GoogleLanguageMapper.toProviderCode(language), "to provider: $language")
            assertEquals(language, GoogleLanguageMapper.fromProviderCode(code), "from provider: $code")
        }
        assertTrue(LanguageCode.GUARANI in supported && LanguageCode.GEORGIAN in supported)
        assertEquals(LanguageCode.PORTUGUESE, GoogleLanguageMapper.fromProviderCode("pt"))
    }
}
