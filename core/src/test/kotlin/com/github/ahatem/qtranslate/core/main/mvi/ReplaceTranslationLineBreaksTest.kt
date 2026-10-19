package com.github.ahatem.qtranslate.core.main.mvi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for #381: the "Remove line breaks from input" setting must also apply
 * before Replace with translation sends externally selected text to the translator.
 *
 * These drive the real [MainStore] intent dispatch rather than the shared helper, because the
 * defect was that this path bypassed the helper entirely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceTranslationLineBreaksTest {

    @Test
    fun `setting ON - replace translation sends cleaned text and pastes its result`() = runTest {
        val fixture = QuickTranslateLineBreaksFixture(this, removeLineBreaks = true)
        val paste = async {
            fixture.store.events.filterIsInstance<MainEvent.PasteTranslation>().first().translatedText
        }
        try {
            fixture.store.dispatch(
                MainIntent.ReplaceWithTranslation("This sentence was\nwrapped over\nthree lines")
            )
            advanceUntilIdle()

            assertEquals(
                "This sentence was wrapped over three lines",
                fixture.translator.received.single(),
                "translator must receive cleaned text, not the raw external selection"
            )
            assertEquals(
                "t:This sentence was wrapped over three lines",
                paste.await(),
                "the replacement result must correspond to the prepared translation input"
            )
            assertEquals(
                false,
                fixture.store.state.value.isReplacingSelection,
                "the replacement loading state must be cleared once the flow completes"
            )
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `setting OFF - replace translation preserves the original selected text`() = runTest {
        val fixture = QuickTranslateLineBreaksFixture(this, removeLineBreaks = false)
        val paste = async {
            fixture.store.events.filterIsInstance<MainEvent.PasteTranslation>().first().translatedText
        }
        try {
            val raw = "This sentence was\nwrapped over\nthree lines"

            fixture.store.dispatch(MainIntent.ReplaceWithTranslation(raw))
            advanceUntilIdle()

            assertEquals(raw, fixture.translator.received.single())
            assertEquals(
                "t:$raw",
                paste.await(),
                "replacement still carries the translator result for the untouched source"
            )
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `setting ON - CRLF and lone CR handling matches prepareInputText`() = runTest {
        val fixture = QuickTranslateLineBreaksFixture(this, removeLineBreaks = true)
        try {
            // Mixed CRLF/LF/CR on purpose. Differential assertion: this path must reuse the
            // established cleanup exactly, including its existing lone-CR behavior.
            val raw = "line one\r\nline two\rline three"
            val expected = prepareInputText(raw, removeLineBreaksEnabled = true)

            fixture.store.dispatch(MainIntent.ReplaceWithTranslation(raw))
            advanceUntilIdle()

            assertEquals(
                expected,
                fixture.translator.received.single(),
                "replace translation must reuse prepareInputText without reinterpreting newlines"
            )
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `blank selection remains a no-op`() = runTest {
        val fixture = QuickTranslateLineBreaksFixture(this, removeLineBreaks = true)
        try {
            fixture.store.dispatch(MainIntent.ReplaceWithTranslation(" \n\t "))
            advanceUntilIdle()

            assertEquals(emptyList(), fixture.translator.received)
            assertEquals(false, fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }
}
