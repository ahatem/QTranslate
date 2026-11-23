package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BingLanguageMapperTest {

    @Test
    fun `translation and tts lists advertise regional Portuguese without a generic row`() {
        for ((name, list) in listOf(
            "translation" to BingLanguageMapper.translationLanguageCodes,
            "tts" to BingLanguageMapper.ttsLanguageCodes
        )) {
            assertTrue(LanguageCode.PORTUGUESE_BRAZIL in list, "$name: Brazilian")
            assertTrue(LanguageCode.PORTUGUESE_PORTUGAL in list, "$name: European")
            assertFalse(LanguageCode.PORTUGUESE in list, "$name: no generic duplicate")
            assertEquals(
                setOf(LanguageCode.PORTUGUESE_BRAZIL, LanguageCode.PORTUGUESE_PORTUGAL),
                list.filter { it.tag.startsWith("pt") }.toSet(),
                "$name: Portuguese rows"
            )
        }
    }

    @Test
    fun `codes map to the provider and back`() {
        // Outbound pairs; the reverse direction is read back as the same language unless noted.
        val roundTrips = listOf(
            LanguageCode.PORTUGUESE_BRAZIL to "pt",
            LanguageCode.PORTUGUESE_PORTUGAL to "pt-PT",
            LanguageCode.SERBIAN to "sr-Cyrl",
            LanguageCode.NORWEGIAN to "nb",
            LanguageCode.AUTO to "auto-detect",
        )
        for ((language, code) in roundTrips) {
            assertEquals(code, BingLanguageMapper.toProviderCode(language), "to provider: $language")
        }
        assertEquals("pt", BingLanguageMapper.toProviderCode(LanguageCode.PORTUGUESE))

        val reads = listOf(
            "pt-br" to LanguageCode.PORTUGUESE_BRAZIL,
            "pt-pt" to LanguageCode.PORTUGUESE_PORTUGAL,
            "pt" to LanguageCode.PORTUGUESE,
            "sr-Cyrl" to LanguageCode.SERBIAN,
            "sr-Latn" to LanguageCode.SERBIAN,
            "nb" to LanguageCode.NORWEGIAN,
            "auto-detect" to LanguageCode.AUTO,
        )
        for ((code, language) in reads) {
            assertEquals(language, BingLanguageMapper.fromProviderCode(code), "from provider: $code")
        }
    }

    @Test
    fun `translation list leaves out hawaiian and keeps serbian`() {
        val list = BingLanguageMapper.translationLanguageCodes
        assertTrue(LanguageCode.SERBIAN in list)
        assertFalse(LanguageCode("haw") in list)
    }
}
