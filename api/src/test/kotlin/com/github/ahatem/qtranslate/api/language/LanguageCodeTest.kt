package com.github.ahatem.qtranslate.api.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageCodeTest {

    @Test
    fun `regional Portuguese constants use standard BCP-47 tags`() {
        assertEquals("pt-BR", LanguageCode.PORTUGUESE_BRAZIL.tag)
        assertEquals("pt-PT", LanguageCode.PORTUGUESE_PORTUGAL.tag)
    }

    @Test
    fun `generic Portuguese remains a valid BCP-47 tag`() {
        assertEquals("pt", LanguageCode.PORTUGUESE.tag)
        assertEquals(LanguageCode.PORTUGUESE, LanguageCode("pt"))
    }

    @Test
    fun `BCP-47 round-trip preserves regional variants`() {
        assertEquals(LanguageCode.PORTUGUESE_BRAZIL, LanguageCode(LanguageCode.PORTUGUESE_BRAZIL.tag))
        assertEquals(LanguageCode.PORTUGUESE_PORTUGAL, LanguageCode(LanguageCode.PORTUGUESE_PORTUGAL.tag))
        assertTrue(LanguageCode.PORTUGUESE_BRAZIL.tag.matches(Regex("^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*$")))
        assertTrue(LanguageCode.PORTUGUESE_PORTUGAL.tag.matches(Regex("^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*$")))
    }

    @Test
    fun `Guarani uses its standard BCP-47 tag`() {
        assertEquals("gn", LanguageCode.GUARANI.tag)
        assertEquals(LanguageCode.GUARANI, LanguageCode("gn"))
    }
}
