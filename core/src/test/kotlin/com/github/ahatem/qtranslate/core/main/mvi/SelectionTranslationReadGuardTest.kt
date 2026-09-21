package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.core.main.domain.usecase.TranslationCompletion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionTranslationReadGuardTest {

    private val completion = TranslationCompletion(requestId = 1L, translatedText = "translated")

    @Test
    fun `successful current translation is read`() {
        assertTrue(SelectionTranslationReadGuard.shouldRead(true, 2L, 2L, completion, true))
    }

    @Test
    fun `normal quick translate never reads`() {
        assertFalse(SelectionTranslationReadGuard.shouldRead(false, 2L, 2L, completion, true))
    }

    @Test
    fun `failure and empty output are silent`() {
        assertFalse(SelectionTranslationReadGuard.shouldRead(true, 2L, 2L, null, true))
        assertFalse(
            SelectionTranslationReadGuard.shouldRead(
                true, 2L, 2L, completion.copy(translatedText = "   "), true
            )
        )
    }

    @Test
    fun `cancelled or superseded request cannot read stale output`() {
        assertFalse(SelectionTranslationReadGuard.shouldRead(true, 2L, 2L, completion, false))
        assertFalse(SelectionTranslationReadGuard.shouldRead(true, 1L, 2L, completion, true))
    }

    @Test
    fun `a then b race keeps only b eligible`() {
        assertFalse(SelectionTranslationReadGuard.shouldRead(true, 1L, 2L, completion, true))
        assertTrue(
            SelectionTranslationReadGuard.shouldRead(
                true, 2L, 2L, completion.copy(requestId = 2L, translatedText = "b"), true
            )
        )
    }
}
