package com.github.ahatem.qtranslate.core.conversation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WordAlignmentTest {

    @Test
    fun `identical words neglecting case and punctuation yield 100 percent agreement and VERIFIED`() {
        val result = WordAlignment.align(
            liveText = "hello world how are you",
            accurateText = "Hello, world! How are you?"
        )
        assertEquals(VerificationState.VERIFIED, result.state)
        assertEquals(1.0, result.agreement, 1e-6)
        assertEquals(5, result.matchedCount)
        assertEquals(listOf(VerificationState.VERIFIED), result.words.map { it.verificationState }.distinct())
    }

    @Test
    fun `single word substitution marks only substituted word CORRECTED and computes agreement`() {
        val result = WordAlignment.align(
            liveText = "we need four apples today",
            accurateText = "we need five apples today"
        )
        assertEquals(VerificationState.CORRECTED, result.state)
        assertEquals(4, result.matchedCount)
        assertEquals(0.8, result.agreement, 1e-6)
        assertEquals(
            listOf(
                VerificationState.VERIFIED,
                VerificationState.VERIFIED,
                VerificationState.CORRECTED,
                VerificationState.VERIFIED,
                VerificationState.VERIFIED
            ),
            result.words.map { it.verificationState }
        )
        assertEquals("five", result.words[2].text)
    }

    @Test
    fun `word insertion marks inserted word CORRECTED and computes agreement over max length`() {
        val result = WordAlignment.align(
            liveText = "please send file now",
            accurateText = "please send the file now"
        )
        assertEquals(VerificationState.CORRECTED, result.state)
        assertEquals(4, result.matchedCount)
        assertEquals(4.0 / 5.0, result.agreement, 1e-6)
        assertEquals(VerificationState.CORRECTED, result.words[2].verificationState)
        assertEquals("the", result.words[2].text)
    }

    @Test
    fun `word deletion takes accurate text and marks segment CORRECTED`() {
        val result = WordAlignment.align(
            liveText = "this is very very good",
            accurateText = "this is very good"
        )
        assertEquals(VerificationState.CORRECTED, result.state)
        assertEquals(4, result.matchedCount)
        assertEquals(4.0 / 5.0, result.agreement, 1e-6)
        assertEquals("this is very good", result.text)
    }

    @Test
    fun `Arabic normalisation unifies alef, ya, ta-marbuta, tatweel and diacritics as VERIFIED`() {
        val live = "إِنَّ اللُّغَةَ العَرَبِيَّةَ جَمِيلَةٌ جدّاً في هذا المـستشفى"
        val accurate = "ان اللغه العربيه جميله جدا في هذا المستشفي"
        val result = WordAlignment.align(liveText = live, accurateText = accurate)

        assertEquals(VerificationState.VERIFIED, result.state)
        assertEquals(1.0, result.agreement, 1e-6)
        assertEquals(8, result.matchedCount)
    }

    @Test
    fun `empty live or accurate side yields 0 agreement and CORRECTED`() {
        val emptyLive = WordAlignment.align(liveText = "", accurateText = "hello world")
        assertEquals(0.0, emptyLive.agreement, 1e-6)
        assertEquals(VerificationState.CORRECTED, emptyLive.state)
        assertEquals(2, emptyLive.words.size)

        val emptyAcc = WordAlignment.align(liveText = "hello world", accurateText = "")
        assertEquals(0.0, emptyAcc.agreement, 1e-6)
        assertEquals(VerificationState.CORRECTED, emptyAcc.state)
        assertEquals(0, emptyAcc.words.size)
    }
}
