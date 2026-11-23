package com.github.ahatem.qtranslate.core.localization

import com.github.ahatem.qtranslate.api.language.LanguageCode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageDisplayNameProviderTest {

    @Test
    fun `regional and minority languages display their English names`() {
        val cases = listOf(
            LanguageCode.PORTUGUESE_BRAZIL to "Portuguese (Brazil)",
            LanguageCode.PORTUGUESE_PORTUGAL to "Portuguese (Portugal)",
            LanguageCode.GUARANI to "Guarani",
        )

        for ((code, expected) in cases) {
            assertEquals(expected, code.getDisplayName(Locale.ENGLISH), code.toString())
        }
    }

    @Test
    fun `country qualifier localizes with the UI locale`() {
        val frenchBr = LanguageCode.PORTUGUESE_BRAZIL.getDisplayName(Locale.FRENCH)
        assertTrue(frenchBr.contains("Portugais"), "expected localized language name, got '$frenchBr'")
        assertTrue(frenchBr.contains("Brésil"), "expected localized country name, got '$frenchBr'")
    }
}
