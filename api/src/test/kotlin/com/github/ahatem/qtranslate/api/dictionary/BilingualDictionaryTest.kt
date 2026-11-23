package com.github.ahatem.qtranslate.api.dictionary

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BilingualDictionaryTest {
    @Test
    fun `automatic target language is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BilingualDictionaryRequest("bank", LanguageCode.ENGLISH, LanguageCode.AUTO)
        }
    }
}
