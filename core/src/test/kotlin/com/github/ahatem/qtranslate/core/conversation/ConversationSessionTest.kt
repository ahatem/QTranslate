package com.github.ahatem.qtranslate.core.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationSessionTest {

    @Test
    fun `withSegment maintains timeline ordering and deterministic tie-breaker for same startMillis`() {
        val s1 = TranscriptSegment(id = "mic-1", source = AudioSource.MICROPHONE, startMillis = 1000, endMillis = 2000, text = "mic first")
        val s2 = TranscriptSegment(id = "sys-1", source = AudioSource.SYSTEM, startMillis = 1000, endMillis = 2000, text = "sys first")
        val s3 = TranscriptSegment(id = "mic-2", source = AudioSource.MICROPHONE, startMillis = 3000, endMillis = 4000, text = "mic later")

        val session = ConversationSession(id = "test", title = "test", createdAt = 0)
            .withSegment(s3)
            .withSegment(s2)
            .withSegment(s1)

        assertEquals(listOf("mic-1", "sys-1", "mic-2"), session.segments.map { it.id })
    }

    @Test
    fun `coalescing merges consecutive segments from same speaker within pause threshold`() {
        val s1 = TranscriptSegment(
            id = "seg-1",
            source = AudioSource.MICROPHONE,
            startMillis = 1000,
            endMillis = 3000,
            text = "Hello world",
            words = listOf(TranscriptWord("Hello", 1000, 1500), TranscriptWord("world", 1600, 2000)),
            language = "en"
        )
        val session = ConversationSession(id = "test", title = "test", createdAt = 0).withSegment(s1)

        // Segment within 1000ms gap (< 2000ms threshold)
        val candidate = session.findCoalesceCandidate(
            source = AudioSource.MICROPHONE,
            startMillis = 4000,
            endMillis = 5500,
            language = "en"
        )
        assertNotNull(candidate)
        assertEquals("seg-1", candidate.id)

        val s2 = TranscriptSegment(
            id = "seg-2",
            source = AudioSource.MICROPHONE,
            startMillis = 4000,
            endMillis = 5500,
            text = "how are you",
            words = listOf(TranscriptWord("how", 4000, 4400), TranscriptWord("are", 4500, 4800), TranscriptWord("you", 4900, 5200)),
            language = "en"
        )
        val merged = coalesceSegments(candidate, s2)
        assertEquals("seg-1", merged.id)
        assertEquals(1000, merged.startMillis)
        assertEquals(5500, merged.endMillis)
        assertEquals("Hello world how are you", merged.text)
        assertEquals(5, merged.words.size)
    }

    @Test
    fun `coalescing rejects if other speaker spoke in between`() {
        val s1 = TranscriptSegment(id = "mic-1", source = AudioSource.MICROPHONE, startMillis = 1000, endMillis = 2000, text = "Are you there?")
        val s2 = TranscriptSegment(id = "sys-1", source = AudioSource.SYSTEM, startMillis = 2200, endMillis = 3000, text = "Yes I am")
        val session = ConversationSession(id = "test", title = "test", createdAt = 0)
            .withSegment(s1)
            .withSegment(s2)

        val candidate = session.findCoalesceCandidate(
            source = AudioSource.MICROPHONE,
            startMillis = 3500,
            endMillis = 5000,
            language = "en"
        )
        assertNull(candidate, "Should not coalesce when another speaker took a turn in between")
    }

    @Test
    fun `coalescing rejects if gap exceeds pause threshold`() {
        val s1 = TranscriptSegment(id = "mic-1", source = AudioSource.MICROPHONE, startMillis = 1000, endMillis = 2000, text = "First sentence")
        val session = ConversationSession(id = "test", title = "test", createdAt = 0).withSegment(s1)

        val candidate = session.findCoalesceCandidate(
            source = AudioSource.MICROPHONE,
            startMillis = 5000, // 3000ms gap > 2000ms
            endMillis = 7000
        )
        assertNull(candidate, "Should not coalesce when pause exceeds 2000ms")
    }

    @Test
    fun `coalescing rejects if combined duration exceeds maximum duration`() {
        val s1 = TranscriptSegment(id = "mic-1", source = AudioSource.MICROPHONE, startMillis = 1000, endMillis = 14000, text = "Very long sentence")
        val session = ConversationSession(id = "test", title = "test", createdAt = 0).withSegment(s1)

        val candidate = session.findCoalesceCandidate(
            source = AudioSource.MICROPHONE,
            startMillis = 14500,
            endMillis = 17000 // 17000 - 1000 = 16000ms > 15000ms
        )
        assertNull(candidate, "Should not coalesce when combined duration exceeds 15000ms")
    }

    @Test
    fun `simultaneous speech from both sources orders archive by settle time and never reorders already-created cards`() {
        // Speaker B (SYSTEM) started speaking first at 500ms, while Speaker A (MICROPHONE) started at 1000ms.
        // Speaker A settles first at 2000ms -> card A is created first in the archive.
        val cardA = TranscriptSegment(
            id = "mic-1",
            source = AudioSource.MICROPHONE,
            startMillis = 1000,
            endMillis = 2000,
            settledAtMillis = 2000,
            text = "Short reply from me"
        )
        var session = ConversationSession(id = "test", title = "test", createdAt = 0).withSegment(cardA)
        assertEquals(listOf("mic-1"), session.segments.map { it.id })

        // Speaker B finishes and settles later at 3200ms (even though startMillis = 500 < cardA.startMillis = 1000).
        // Card B must be placed AFTER card A so card A never jumps or swaps position!
        val cardB = TranscriptSegment(
            id = "sys-1",
            source = AudioSource.SYSTEM,
            startMillis = 500,
            endMillis = 3200,
            settledAtMillis = 3200,
            text = "Longer overlapping statement from call"
        )
        session = session.withSegment(cardB)
        assertEquals(listOf("mic-1", "sys-1"), session.segments.map { it.id })

        // Updating cardA in place (e.g. when accurate transcription finishes) keeps its exact position
        session = session.withSegment(cardA.copy(text = "Short reply from me (verified)", draft = false))
        assertEquals(listOf("mic-1", "sys-1"), session.segments.map { it.id })

        // Tie-break when both settle at the exact same settledAtMillis: MICROPHONE before SYSTEM
        val tieSys = TranscriptSegment(id = "sys-2", source = AudioSource.SYSTEM, startMillis = 4000, endMillis = 5000, settledAtMillis = 5000, text = "sys")
        val tieMic = TranscriptSegment(id = "mic-2", source = AudioSource.MICROPHONE, startMillis = 4200, endMillis = 5000, settledAtMillis = 5000, text = "mic")
        val tieSession = session.withSegment(tieSys).withSegment(tieMic)
        assertEquals(listOf("mic-1", "sys-1", "mic-2", "sys-2"), tieSession.segments.map { it.id })
    }

    @Test
    fun `coalescing never touches a non-last card even when other speaker card had earlier startMillis`() {
        // Card 1: MICROPHONE (start=1000, end=2000, settled=2000)
        // Card 2: SYSTEM (start=500, end=2100, settled=2100) -> LAST card in archive
        val card1 = TranscriptSegment(
            id = "mic-1",
            source = AudioSource.MICROPHONE,
            startMillis = 1000,
            endMillis = 2000,
            settledAtMillis = 2000,
            text = "First part"
        )
        val card2 = TranscriptSegment(
            id = "sys-1",
            source = AudioSource.SYSTEM,
            startMillis = 500,
            endMillis = 2100,
            settledAtMillis = 2100,
            text = "Overlapping call audio"
        )
        val session = ConversationSession(id = "test", title = "test", createdAt = 0)
            .withSegment(card1)
            .withSegment(card2)

        assertEquals(listOf("mic-1", "sys-1"), session.segments.map { it.id })

        // MICROPHONE speaks again at 2500ms (only 500ms after card1.endMillis = 2000ms).
        // Because card1 is NOT the last card in the archive, coalescing MUST NOT touch card1.
        val candidate = session.findCoalesceCandidate(
            source = AudioSource.MICROPHONE,
            startMillis = 2500,
            endMillis = 3500
        )
        assertNull(candidate, "Must never coalesce into a non-last archive card")
        assertFalse(session.canCoalesce(card1, AudioSource.MICROPHONE, 2500, 3500))
    }
}
