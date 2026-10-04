package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BingLanguageMapperTest {

    @Test
    fun `translation list advertises regional Portuguese without a duplicate generic row`() {
        val list = BingLanguageMapper.translationLanguageCodes
        assertTrue(LanguageCode.PORTUGUESE_BRAZIL in list)
        assertTrue(LanguageCode.PORTUGUESE_PORTUGAL in list)
        assertFalse(LanguageCode.PORTUGUESE in list)
        val portugueseRows = list.filter { it.tag.startsWith("pt") }
        assertEquals(setOf(LanguageCode.PORTUGUESE_BRAZIL, LanguageCode.PORTUGUESE_PORTUGAL), portugueseRows.toSet())
    }

    @Test
    fun `brazilian portuguese maps through to the bing codes`() = runBlocking {
        assertEquals("pt", BingLanguageMapper.toProviderCode(LanguageCode.PORTUGUESE_BRAZIL))
        assertEquals("pt-PT", BingLanguageMapper.toProviderCode(LanguageCode.PORTUGUESE_PORTUGAL))
        assertEquals("pt", BingLanguageMapper.toProviderCode(LanguageCode.PORTUGUESE))
    }

    @Test
    fun `serbian is sent with its script`() {
        assertEquals("sr-Cyrl", BingLanguageMapper.toProviderCode(LanguageCode.SERBIAN))
    }

    @Test
    fun `either serbian script is read back as the single serbian choice`() {
        assertEquals(LanguageCode.SERBIAN, BingLanguageMapper.fromProviderCode("sr-Cyrl"))
        assertEquals(LanguageCode.SERBIAN, BingLanguageMapper.fromProviderCode("sr-Latn"))
    }

    @Test
    fun `languages this endpoint does not take are still mapped`() {
        assertEquals("nb", BingLanguageMapper.toProviderCode(LanguageCode.NORWEGIAN))
        assertEquals("auto-detect", BingLanguageMapper.toProviderCode(LanguageCode.AUTO))
        assertEquals(LanguageCode.NORWEGIAN, BingLanguageMapper.fromProviderCode("nb"))
        assertEquals(LanguageCode.AUTO, BingLanguageMapper.fromProviderCode("auto-detect"))
    }

    @Test
    fun `translation list leaves out hawaiian and keeps serbian`() {
        val list = BingLanguageMapper.translationLanguageCodes
        assertTrue(LanguageCode.SERBIAN in list)
        assertFalse(LanguageCode("haw") in list)
    }

    @Test
    fun `reverse mapping yields canonical language codes`() {
        assertEquals(LanguageCode.PORTUGUESE_BRAZIL, BingLanguageMapper.fromProviderCode("pt-br"))
        assertEquals(LanguageCode.PORTUGUESE_PORTUGAL, BingLanguageMapper.fromProviderCode("pt-pt"))
        assertEquals(LanguageCode.PORTUGUESE, BingLanguageMapper.fromProviderCode("pt"))
    }

    @Test
    fun `tts list advertises both brazilian and european voices`() {
        val list = BingLanguageMapper.ttsLanguageCodes.toSet()
        assertTrue(LanguageCode.PORTUGUESE_BRAZIL in list)
        assertTrue(LanguageCode.PORTUGUESE_PORTUGAL in list)
        assertFalse(LanguageCode.PORTUGUESE in list)
    }
}
