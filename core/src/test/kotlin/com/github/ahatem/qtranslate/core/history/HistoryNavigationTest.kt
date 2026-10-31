package com.github.ahatem.qtranslate.core.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryNavigationTest {
    private val first = HistorySnapshot("first", "one", "en", "fr", "test", timestamp = 1)
    private val second = HistorySnapshot("second", "two", "en", "fr", "test", timestamp = 2)
    private val history = listOf(first, second)

    @Test fun `navigation includes blank position and stops at boundaries`() {
        assertNull(previousHistory(emptyList(), 0))
        assertNull(nextHistory(emptyList(), 0))
        assertNull(previousHistory(history, 0))
        assertEquals(HistorySelection(1, second), previousHistory(history, 2))
        assertEquals(HistorySelection(1, second), nextHistory(history, 0))
        assertEquals(HistorySelection(2, null), nextHistory(history, 1))
        assertNull(nextHistory(history, 2))
    }

    @Test fun `restoring positions cursor after first matching entry or keeps it for absent entry`() {
        assertEquals(1, restoredHistoryIndex(listOf(first, first), 0, first))
        assertEquals(2, restoredHistoryIndex(history, 2, first.copy(inputText = "absent")))
    }
}
