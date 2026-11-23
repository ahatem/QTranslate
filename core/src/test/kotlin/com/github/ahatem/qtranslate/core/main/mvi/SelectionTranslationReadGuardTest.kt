package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.core.workspace.translation.TranslationCompletion
import kotlin.test.Test
import kotlin.test.assertEquals

/** Stale or failed output must never be read aloud. */
class SelectionTranslationReadGuardTest {

    private val completion = TranslationCompletion(requestId = 1L, translatedText = "translated")

    private class Case(
        val label: String,
        val readRequested: Boolean = true,
        val generation: Long = 2L,
        val completion: TranslationCompletion?,
        val stillCurrent: Boolean = true,
        val expected: Boolean,
    )

    @Test
    fun `translation is read only when requested, current and non blank`() {
        val cases = listOf(
            Case("successful current translation", completion = completion, expected = true),
            Case("normal quick translate never reads", readRequested = false, completion = completion, expected = false),
            Case("failure", completion = null, expected = false),
            Case("empty output", completion = completion.copy(translatedText = "   "), expected = false),
            Case("cancelled request", completion = completion, stillCurrent = false, expected = false),
            Case("superseded selection generation", generation = 1L, completion = completion, expected = false),
        )

        for (case in cases) {
            val actual = SelectionTranslationReadGuard.shouldRead(
                case.readRequested, case.generation, 2L, case.completion, case.stillCurrent
            )
            assertEquals(case.expected, actual, case.label)
        }
    }

    @Test
    fun `source is read only when requested, current and non blank`() {
        val cases = listOf(
            Triple("current selection", Triple(true, 2L, "captured"), true),
            Triple("newer selection generation exists", Triple(true, 1L, "captured"), false),
            Triple("blank selection", Triple(true, 2L, ""), false),
            Triple("not requested", Triple(false, 2L, "captured"), false),
        )

        for ((label, input, expected) in cases) {
            val (requested, generation, text) = input
            assertEquals(expected, SelectionTranslationReadGuard.shouldReadSource(requested, generation, 2L, text), label)
        }
    }
}
