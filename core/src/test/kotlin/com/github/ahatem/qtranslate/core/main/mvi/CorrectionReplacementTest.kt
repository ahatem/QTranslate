package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.spellchecker.Correction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CorrectionReplacementTest {
    @Test fun `selected repeated occurrence is replaced at its exact range`() {
        val text = "teh teh"
        val second = Correction("teh", 4, 7, listOf("the"))
        assertEquals("teh the", replaceCorrectionAtRange(text, second, "the"))
    }

    @Test fun `emoji and RTL ranges preserve unrelated text`() {
        assertEquals("😀 the", replaceCorrectionAtRange("😀 teh", Correction("teh", 3, 6, listOf("the")), "the"))
        assertEquals("مرحبا!", replaceCorrectionAtRange("مرحب!", Correction("مرحب", 0, 4, listOf("مرحبا")), "مرحبا"))
    }

    @Test fun `stale or mismatched correction cannot alter input`() {
        assertNull(replaceCorrectionAtRange("the teh", Correction("teh", 0, 3, listOf("the")), "the"))
        assertNull(replaceCorrectionAtRange("teh", Correction("teh", 2, 5, listOf("the")), "the"))
    }
}
