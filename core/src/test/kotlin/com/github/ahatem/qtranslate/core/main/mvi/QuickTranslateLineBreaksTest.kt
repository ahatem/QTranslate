package com.github.ahatem.qtranslate.core.main.mvi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for #353: the "Remove line breaks from input" setting must also apply
 * before Quick Translate sends externally selected text to the translator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickTranslateLineBreaksTest {

    @Test
    fun `setting ON - quick translate sends cleaned single-line text`() = runTest {
        val fixture = QuickTranslateLineBreaksFixture(this, removeLineBreaks = true)
        try {
            fixture.store.dispatch(
                MainIntent.ShowQuickTranslate("This is one\nsentence wrapped\nacross PDF lines")
            )
            advanceUntilIdle()

            assertEquals(
                "This is one sentence wrapped across PDF lines",
                fixture.translator.received.single(),
                "translator must receive cleaned text, not raw newlines"
            )
            assertEquals(
                "This is one sentence wrapped across PDF lines",
                fixture.store.state.value.inputText,
                "the stored source must match the text that was translated"
            )
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `setting OFF - quick translate preserves original selected text`() = runTest {
        val fixture = QuickTranslateLineBreaksFixture(this, removeLineBreaks = false)
        try {
            val raw = "This is one\nsentence wrapped\nacross PDF lines"
            fixture.store.dispatch(MainIntent.ShowQuickTranslate(raw))
            advanceUntilIdle()

            assertEquals(raw, fixture.translator.received.single())
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `setting ON - CRLF cleanup matches UpdateInputText semantics`() = runTest {
        val fixture = QuickTranslateLineBreaksFixture(this, removeLineBreaks = true)
        try {
            // Mixed CRLF/LF/CR on purpose. The established cleanup turns "\n" into a space and
            // drops "\r" without inserting one, so a lone "\r" joins its neighbours. #353
            // reuses exactly that behavior; it must not "improve" it here.
            val raw = "line one\r\nline two\rline three"

            fixture.store.dispatch(MainIntent.UpdateInputText(raw))
            val cleanedByTyping = fixture.store.state.value.inputText
            assertEquals(
                "line one line twoline three",
                cleanedByTyping,
                "normal input cleanup semantics must remain unchanged"
            )

            fixture.store.dispatch(MainIntent.ShowQuickTranslate(raw))
            advanceUntilIdle()

            assertEquals(
                cleanedByTyping,
                fixture.translator.received.single(),
                "quick translate must reuse the normal input cleanup, including its CR behavior"
            )
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `retranslate reuses cleaned source without reintroducing raw text`() = runTest {
        val fixture = QuickTranslateLineBreaksFixture(this, removeLineBreaks = true)
        try {
            fixture.store.dispatch(
                MainIntent.ShowQuickTranslate("This is one\nsentence wrapped\nacross PDF lines")
            )
            advanceUntilIdle()
            fixture.store.dispatch(MainIntent.RetranslateQuickTranslate)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "This is one sentence wrapped across PDF lines",
                    "This is one sentence wrapped across PDF lines"
                ),
                fixture.translator.received
            )
        } finally {
            fixture.cleanUp()
        }
    }
}
