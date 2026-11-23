package com.github.ahatem.qtranslate.api.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageCodeTest {

    @Test
    fun `constants use standard BCP-47 tags that round-trip through the constructor`() {
        val bcp47 = Regex("^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})*$")
        val expected = mapOf(
            LanguageCode.PORTUGUESE_BRAZIL to "pt-BR",
            LanguageCode.PORTUGUESE_PORTUGAL to "pt-PT",
            LanguageCode.PORTUGUESE to "pt",
            LanguageCode.GUARANI to "gn"
        )
        for ((code, tag) in expected) {
            assertEquals(tag, code.tag)
            assertEquals(code, LanguageCode(tag), "$tag round-trip")
            assertTrue(code.tag.matches(bcp47), "$tag is not a valid BCP-47 tag")
        }
    }
}
