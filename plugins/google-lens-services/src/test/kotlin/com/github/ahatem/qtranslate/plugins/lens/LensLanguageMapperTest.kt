package com.github.ahatem.qtranslate.plugins.lens

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals

class LensLanguageMapperTest {

    @Test
    fun `codes map to the provider and back`() {
        val pairs = listOf(
            LanguageCode.CHINESE_SIMPLIFIED to "zh-CN",
            LanguageCode.CHINESE_TRADITIONAL to "zh-TW",
            LanguageCode.HEBREW to "iw",
            LanguageCode.ENGLISH to "en",
            LanguageCode.SPANISH to "es",
            LanguageCode.FRENCH to "fr",
            LanguageCode.GERMAN to "de",
        )
        for ((language, code) in pairs) {
            assertEquals(code, LensLanguageMapper.toProviderCode(language), "to provider: $language")
            assertEquals(language, LensLanguageMapper.fromProviderCode(code), "from provider: $code")
        }
    }

    @Test
    fun `unknown or regional provider code falls back gracefully`() {
        assertEquals(LanguageCode("pt"), LensLanguageMapper.fromProviderCode("pt-XYZ"), "unknown region falls back to the base language")
        assertEquals(LanguageCode("en"), LensLanguageMapper.fromProviderCode("!!!invalid-tag???"), "invalid tag falls back to English")
    }
}
