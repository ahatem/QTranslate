package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import kotlinx.coroutines.runBlocking
import com.github.michaelbull.result.getOr
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
    fun `outbound codes preserve the region`() {
        assertEquals("pt-BR", GoogleLanguageMapper.toProviderCode(LanguageCode.PORTUGUESE_BRAZIL))
        assertEquals("pt-PT", GoogleLanguageMapper.toProviderCode(LanguageCode.PORTUGUESE_PORTUGAL))
    }

    @Test
    fun `reverse mapping yields canonical language codes`() {
        assertEquals(LanguageCode.PORTUGUESE_BRAZIL, GoogleLanguageMapper.fromProviderCode("pt-BR"))
        assertEquals(LanguageCode.PORTUGUESE_PORTUGAL, GoogleLanguageMapper.fromProviderCode("pt-PT"))
        assertEquals(LanguageCode.PORTUGUESE, GoogleLanguageMapper.fromProviderCode("pt"))
    }

    @Test
    fun `supported set advertises Guarani`() = runBlocking {
        val supported = GoogleLanguageMapper.getSupportedLanguages().getOr(emptySet())
        assertTrue(LanguageCode.GUARANI in supported)
    }

    @Test
    fun `Guarani maps to and from its standard provider code`() {
        assertEquals("gn", GoogleLanguageMapper.toProviderCode(LanguageCode.GUARANI))
        assertEquals(LanguageCode.GUARANI, GoogleLanguageMapper.fromProviderCode("gn"))
    }
}
