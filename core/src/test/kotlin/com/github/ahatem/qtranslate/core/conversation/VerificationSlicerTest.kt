package com.github.ahatem.qtranslate.core.conversation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerificationSlicerTest {

    @Test
    fun `chooseWordBoundaryCut lands on word boundary with pause gap at least 150ms nearest to 4_5-6s window`() {
        // Words with tight 30ms gaps, except after word 8 (at 4800ms) where there is a 220ms pause
        val words = listOf(
            TranscriptWord("one", 0, 500),
            TranscriptWord("two", 530, 1100),
            TranscriptWord("three", 1130, 1700),
            TranscriptWord("four", 1730, 2300),
            TranscriptWord("five", 2330, 2900),
            TranscriptWord("six", 2930, 3500),
            TranscriptWord("seven", 3530, 4150),
            TranscriptWord("eight", 4180, 4800), // <-- 220 ms pause after "eight" (4800..5020)
            TranscriptWord("nine", 5020, 5500),  // <-- only 20 ms gap after "nine"
            TranscriptWord("ten", 5520, 5950)
        )

        val decision = VerificationSlicer.chooseWordBoundaryCut(
            words = words,
            sliceStartMillis = 0L,
            currentEndMillis = 5970L
        )

        assertNotNull(decision)
        assertEquals(8, decision!!.totalWordsIncluded)
        assertEquals("eight", decision.sliceWords.last().text)
        assertTrue(decision.cutMillis in 4800L..5020L, "Cut must land inside the pause gap after 'eight', never inside a word")
    }

    @Test
    fun `chooseWordBoundaryCut returns null before 6s when no pause gap at least 150ms exists`() {
        val continuousWords = (0 until 9).map { i ->
            TranscriptWord("w$i", i * 520L, i * 520L + 500L) // only 20ms gap between every word
        }
        val decision = VerificationSlicer.chooseWordBoundaryCut(
            words = continuousWords,
            sliceStartMillis = 0L,
            currentEndMillis = 4700L
        )
        assertNull(decision, "Should wait when under 6s and no >=150ms pause gap exists")
    }

    @Test
    fun `filterOutOverlapWords drops words inside the 500ms pre-cut overlap`() {
        // Suppose sliceStartMillis = 4800ms, and we sent audio starting at 4300ms (500ms overlap).
        // The accurate recognizer returned the overlap word "eight" (4350..4780) plus new words "nine" and "ten".
        val recognized = listOf(
            TranscriptWord("eight", 4350, 4780),
            TranscriptWord("nine", 5020, 5500),
            TranscriptWord("ten", 5540, 5980)
        )

        val (text, kept) = VerificationSlicer.filterOutOverlapWords(
            words = recognized,
            fallbackText = "eight nine ten",
            sliceStartMillis = 4800L
        )

        assertEquals("nine ten", text)
        assertEquals(listOf("nine", "ten"), kept.map { it.text })
    }
}
