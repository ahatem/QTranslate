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
    fun `host accepts plugins built against an earlier minor of the same major`() {
        assertEquals("2.0.0", ApiVersion.VERSION)
        assertIs<ApiVersion.CompatibilityResult.Compatible>(ApiVersion.isCompatible("2.0.0"))
    }

    @Test
    fun `host rejects plugins built against the previous major`() {
        // The whole point of the major bump: a v1 plugin declares an id and infers its capability
        // from the interfaces it implements, neither of which the host reads any more. Loading one
        // would fail at the first call rather than at load, so it is refused here.
        assertIs<ApiVersion.CompatibilityResult.Incompatible>(ApiVersion.isCompatible("1.2.0"))
    }
}
