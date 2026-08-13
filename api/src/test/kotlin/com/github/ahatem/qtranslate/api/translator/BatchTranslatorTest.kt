package com.github.ahatem.qtranslate.api.translator

import com.github.ahatem.qtranslate.api.core.ApiVersion
import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BatchTranslatorTest {
    @Test
    fun `batch request preserves ordered texts`() {
        val request = BatchTranslationRequest(
            texts = listOf("First", "Second"),
            sourceLanguage = LanguageCode("en"),
            targetLanguage = LanguageCode("fr")
        )

        assertEquals(listOf("First", "Second"), request.texts)
    }

    @Test
    fun `batch request rejects blank items`() {
        assertFailsWith<IllegalArgumentException> {
            BatchTranslationRequest(
                texts = listOf("First", " "),
                sourceLanguage = LanguageCode("en"),
                targetLanguage = LanguageCode("fr")
            )
        }
    }

    @Test
    fun `host accepts plugins built against the previous minor API`() {
        assertEquals("1.2.0", ApiVersion.VERSION)
        assertIs<ApiVersion.CompatibilityResult.Compatible>(ApiVersion.isCompatible("1.0.0"))
    }
}
