package com.github.ahatem.qtranslate.api.dictionary

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BilingualDictionaryTest {
    @Test
    fun `bilingual request preserves the selected language pair`() {
        val request = BilingualDictionaryRequest(
            "bank",
            LanguageCode.ENGLISH,
            LanguageCode.FRENCH
        )

        assertEquals(LanguageCode.ENGLISH, request.sourceLanguage)
        assertEquals(LanguageCode.FRENCH, request.targetLanguage)
    }

    @Test
    fun `automatic target language is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BilingualDictionaryRequest("bank", LanguageCode.ENGLISH, LanguageCode.AUTO)
        }
    }
}
