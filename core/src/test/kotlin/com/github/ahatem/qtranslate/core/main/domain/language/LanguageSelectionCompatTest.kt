package com.github.ahatem.qtranslate.core.main.domain.language

import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageSelectionCompatTest {

    private val regionalProvider = listOf(
        LanguageCode.AUTO, LanguageCode.ENGLISH,
        LanguageCode.PORTUGUESE_BRAZIL, LanguageCode.PORTUGUESE_PORTUGAL
    )

    @Test
    fun `legacy generic pt selection remains supported when provider advertises regional variants`() {
        assertTrue(LanguageSelectionCompat.isSupported(LanguageCode.PORTUGUESE, regionalProvider))
    }

    @Test
    fun `regional selection requires its own tag`() {
        assertTrue(LanguageSelectionCompat.isSupported(LanguageCode.PORTUGUESE_BRAZIL, regionalProvider))
        assertFalse(LanguageSelectionCompat.isSupported(LanguageCode.PORTUGUESE_BRAZIL, listOf(LanguageCode.ENGLISH, LanguageCode.PORTUGUESE_PORTUGAL)))
    }

    @Test
    fun `visible languages keeps legacy selected pt without offering it as a new choice`() {
        val visible = LanguageSelectionCompat.visibleLanguages(regionalProvider, listOf(LanguageCode.PORTUGUESE))
        assertEquals(regionalProvider + LanguageCode.PORTUGUESE, visible)
        val fresh = LanguageSelectionCompat.visibleLanguages(regionalProvider, listOf(LanguageCode.FRENCH))
        assertEquals(regionalProvider, fresh)
    }

    @Test
    fun `target resolution keeps existing pt instead of falling back to another language`() {
        val resolved = LanguageSelectionCompat.resolveTargetLanguage(
            LanguageCode.PORTUGUESE, regionalProvider, "pt"
        )
        assertEquals(LanguageCode.PORTUGUESE, resolved)
    }

    @Test
    fun `source legacy pt stays visible in the language list`() {
        val visible = LanguageSelectionCompat.visibleLanguages(regionalProvider, listOf(LanguageCode.PORTUGUESE, LanguageCode.PORTUGUESE_PORTUGAL))
        assertTrue(LanguageCode.PORTUGUESE in visible)
        assertTrue(LanguageCode.PORTUGUESE_PORTUGAL in visible)
    }

    @Test
    fun `target resolution still falls back when the stored language is genuinely unsupported`() {
        val resolved = LanguageSelectionCompat.resolveTargetLanguage(
            LanguageCode.FRENCH, regionalProvider, "en"
        )
        assertEquals(LanguageCode.ENGLISH, resolved)
    }
}
