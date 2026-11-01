package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.shared.StatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MainStoreSessionEventsTest {
    @Test fun `replaced lookup statuses are discarded even when already buffered`() = runTest {
        val fixture = QuickTranslateLineBreaksFixture(this, removeLineBreaks = false)
        try {
            // No collector yet: both outcomes reach the buffered event channel before replacement.
            fixture.store.dispatch(MainIntent.LookupWord("old", LanguageCode.ENGLISH)); runCurrent()
            fixture.store.dispatch(MainIntent.LookupWord("new", LanguageCode.ENGLISH)); runCurrent()
            fixture.store.dispatch(MainIntent.SearchImages("old", LanguageCode.ENGLISH)); runCurrent()
            fixture.store.dispatch(MainIntent.SearchImages("new", LanguageCode.ENGLISH)); runCurrent()
            val events = mutableListOf<MainEvent>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.store.events.collect { events += it }
            }
            runCurrent()
            assertEquals(listOf(StatusCode.NoDictionaryServiceActive, StatusCode.NoImageSearchServiceActive),
                events.filterIsInstance<MainEvent.UpdateStatusBar>().map { it.code })
            collector.cancel()
        } finally { fixture.cleanUp() }
    }
}
