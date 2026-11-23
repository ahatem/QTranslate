package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The "Remove line breaks from input" setting must apply before externally selected text reaches
 * the translator, through both Quick Translate and Replace with translation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LineBreakRemovalTest {

    private class Case(val label: String, val removeLineBreaks: Boolean, val raw: String, val expected: String)

    private val wrapped = "This is one\nsentence wrapped\nacross PDF lines"

    // The established cleanup turns "\n" into a space and drops "\r" without one, so a lone "\r"
    // joins its neighbours. Both entry points must reuse it unchanged.
    private val cases = listOf(
        Case("setting on", true, wrapped, "This is one sentence wrapped across PDF lines"),
        Case("setting off", false, wrapped, wrapped),
        Case("setting on, mixed CRLF and lone CR", true, "line one\r\nline two\rline three", "line one line twoline three"),
    )

    @Test
    fun `quick translate sends the text the setting prescribes`() = runTest {
        for (case in cases) {
            val fixture = fixture(case.removeLineBreaks)
            try {
                fixture.store.dispatch(MainIntent.ShowQuickTranslate(case.raw))
                advanceUntilIdle()

                assertEquals(case.expected, fixture.translator.received.single(), "quick, ${case.label}")
                assertEquals(case.expected, fixture.store.state.value.inputText, "quick source, ${case.label}")
            } finally {
                fixture.cleanUp()
            }
        }
    }

    @Test
    fun `replace translation sends the text the setting prescribes and pastes its result`() = runTest {
        for (case in cases) {
            val fixture = fixture(case.removeLineBreaks)
            val paste = async {
                fixture.store.events.filterIsInstance<MainEvent.PasteTranslation>().first().translatedText
            }
            try {
                fixture.store.dispatch(MainIntent.ReplaceWithTranslation(case.raw))
                advanceUntilIdle()

                assertEquals(case.expected, fixture.translator.received.single(), "replace, ${case.label}")
                assertEquals("t:${case.expected}", paste.await(), "pasted result, ${case.label}")
                assertEquals(false, fixture.store.state.value.isReplacingSelection, "loading cleared, ${case.label}")
            } finally {
                fixture.cleanUp()
            }
        }
    }

    @Test
    fun `retranslate reuses cleaned source without reintroducing raw text`() = runTest {
        val fixture = fixture(removeLineBreaks = true)
        try {
            fixture.store.dispatch(MainIntent.ShowQuickTranslate(wrapped))
            advanceUntilIdle()
            fixture.store.dispatch(MainIntent.RetranslateQuickTranslate)
            advanceUntilIdle()

            val cleaned = "This is one sentence wrapped across PDF lines"
            assertEquals(listOf(cleaned, cleaned), fixture.translator.received)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `blank selection remains a no-op`() = runTest {
        val fixture = fixture(removeLineBreaks = true)
        try {
            fixture.store.dispatch(MainIntent.ReplaceWithTranslation(" \n\t "))
            advanceUntilIdle()

            assertEquals(emptyList(), fixture.translator.received)
            assertEquals(false, fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    private class Fixture(val main: MainStoreFixture, val translator: RecordingTranslator) {
        val store get() = main.store
        fun cleanUp() = main.cleanUp()
    }

    private fun TestScope.fixture(removeLineBreaks: Boolean): Fixture {
        val translator = RecordingTranslator()
        val main = MainStoreFixture(
            services = mapOf(translator.key to translator),
            configuration = MainStoreFixture.configuration(mapOf(ServiceRole.TRANSLATOR to translator.key)) {
                copy(isRemoveLineBreaksEnabled = removeLineBreaks)
            },
            testScope = this,
            collectEvents = false,
        )
        return Fixture(main, translator)
    }
}