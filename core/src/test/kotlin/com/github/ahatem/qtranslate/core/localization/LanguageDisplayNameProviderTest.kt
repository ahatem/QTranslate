package com.github.ahatem.qtranslate.core.localization

import com.github.ahatem.qtranslate.api.language.LanguageCode
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LanguageDisplayNameProviderTest {

    @Test
    fun `pt-BR displays Portuguese Brazil`() {
        assertEquals("Portuguese (Brazil)", LanguageCode.PORTUGUESE_BRAZIL.getDisplayName(Locale.ENGLISH))
    }

    @Test
    fun `pt-PT displays Portuguese Portugal`() {
        assertEquals("Portuguese (Portugal)", LanguageCode.PORTUGUESE_PORTUGAL.getDisplayName(Locale.ENGLISH))
    }

    @Test
    fun `regional variants do not collapse to identical display text`() {
        val br = LanguageCode.PORTUGUESE_BRAZIL.getDisplayName(Locale.ENGLISH)
        val pt = LanguageCode.PORTUGUESE_PORTUGAL.getDisplayName(Locale.ENGLISH)
        val generic = LanguageCode.PORTUGUESE.getDisplayName(Locale.ENGLISH)
        assertNotEquals(br, pt)
        assertNotEquals(br, generic)
    }

    @Test
    fun `country qualifier localizes with the UI locale`() {
        val frenchBr = LanguageCode.PORTUGUESE_BRAZIL.getDisplayName(Locale.FRENCH)
        assertTrue(frenchBr.contains("Portugais"), "expected localized language name, got '$frenchBr'")
        assertTrue(frenchBr.contains("Brésil"), "expected localized country name, got '$frenchBr'")
    }
}
