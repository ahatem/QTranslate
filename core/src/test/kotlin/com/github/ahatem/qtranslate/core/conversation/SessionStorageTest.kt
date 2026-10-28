package com.github.ahatem.qtranslate.core.conversation

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionStorageTest {

    private fun segment(id: String, start: Long, text: String, source: AudioSource = AudioSource.SYSTEM) =
        TranscriptSegment(id = id, source = source, startMillis = start, endMillis = start + 1_000, text = text)

    @Test
    fun `segments stay in spoken order whatever order they finish in`() {
        val session = ConversationSession(id = "s", title = "t", createdAt = 0)
            .withSegment(segment("b", 5_000, "second"))
            .withSegment(segment("a", 1_000, "first"))
            .withSegment(segment("c", 9_000, "third"))
            .withSegment(segment("b", 5_000, "second, transcribed"))
        assertEquals(listOf("first", "second, transcribed", "third"), session.segments.map { it.text })
    }

    @Test
    fun `sessions save, reopen, search and delete`() {
        val repository = SessionRepository(createTempDirectory("sessions").toFile())
        val older = ConversationSession(id = "1", title = "Monday raid", createdAt = 1_000)
            .withSegment(segment("x", 0, "the server keeps timing out"))
        val newer = ConversationSession(id = "2", title = "Planning", createdAt = 2_000)
            .withSegment(segment("y", 0, "Let's figure out the release"))
        repository.save(older)
        repository.save(newer)

        assertEquals(listOf("2", "1"), repository.list().map { it.id })
        assertEquals(older, repository.load("1"))

        val hits = repository.search("FIGURE out")
        assertEquals(listOf("2"), hits.map { it.session.id })
        assertEquals(listOf("y"), hits.single().segments.map { it.id })
        assertEquals(listOf("1"), repository.search("raid").map { it.session.id }, "titles match too")

        assertTrue(repository.delete("1"))
        assertNull(repository.load("1"))
        repository.root.deleteRecursively()
    }

    @Test
    fun `a session left recording by a crash is recovered as interrupted with playable audio`() {
        val repository = SessionRepository(createTempDirectory("sessions").toFile())
        val session = ConversationSession(
            id = "crashed", title = "t", createdAt = 0, status = SessionStatus.RECORDING,
            streams = listOf(SessionStream(AudioSource.MICROPHONE, "Mic", offsetMillis = 0))
        )
        repository.save(session)
        val writer = WavFileWriter(repository.audioFile("crashed", AudioSource.MICROPHONE))
        writer.write(tone(400)) // never closed

        val recovered = repository.recoverInterrupted().single()

        assertEquals(SessionStatus.INTERRUPTED, recovered.status)
        assertEquals(400, recovered.durationMillis)
        assertEquals(SessionStatus.INTERRUPTED, repository.load("crashed")?.status)
        writer.close()
        repository.root.deleteRecursively()
    }

    @Test
    fun `a corrupt session file is skipped rather than breaking the list`() {
        val repository = SessionRepository(createTempDirectory("sessions").toFile())
        repository.save(ConversationSession(id = "ok", title = "t", createdAt = 0))
        java.io.File(repository.folderOf("broken").apply { mkdirs() }, "session.json").writeText("{ not json")
        assertEquals(listOf("ok"), repository.list().map { it.id })
        repository.root.deleteRecursively()
    }

    @Test
    fun `export lists each spoken line with its time and source`() {
        val repository = SessionRepository(createTempDirectory("sessions").toFile())
        val session = ConversationSession(id = "s", title = "Call", createdAt = 0)
            .withSegment(segment("a", 65_000, "hello", AudioSource.MICROPHONE))
            .withSegment(TranscriptSegment("b", AudioSource.SYSTEM, 70_000, 71_000, failed = true))
        val text = repository.exportText(session) { if (it == AudioSource.MICROPHONE) "Me" else "Call" }
        assertTrue("[1:05] Me: hello" in text)
        assertTrue(text.lines().none { it.startsWith("[1:10]") }, "untranscribed segments are left out")
        repository.root.deleteRecursively()
    }

    @Test
    fun `exportSrt produces valid subtitle entries with millisecond timestamps`() {
        val repository = SessionRepository(createTempDirectory("sessions").toFile())
        val session = ConversationSession(id = "s", title = "Call", createdAt = 0)
            .withSegment(TranscriptSegment("a", AudioSource.MICROPHONE, 65_120, 68_450, text = "hello world"))
        val srt = repository.exportSrt(session) { if (it == AudioSource.MICROPHONE) "Me" else "Call" }
        assertTrue("1" in srt)
        assertTrue("00:01:05,120 --> 00:01:08,450" in srt)
        assertTrue("Me: hello world" in srt)
        repository.root.deleteRecursively()
    }

    @Test
    fun `concurrent hovers over the same word make one request and the answer is cached`() = runTest {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val cache = TranslationCache(this, { text, _ ->
            calls.incrementAndGet()
            gate.await()
            Ok("«$text»")
        })

        val results = List(5) { async { cache.translate("figure out", LanguageCode.ARABIC) } }
        gate.complete(Unit)
        assertTrue(results.awaitAll().all { it == Ok("«figure out»") })
        assertEquals(Ok("«figure out»"), cache.translate("figure out.", LanguageCode.ARABIC), "punctuation is ignored")
        assertEquals(1, calls.get())
        assertEquals("«figure out»", cache.peek("figure out", LanguageCode.ARABIC))
    }

    @Test
    fun `failures are not cached so the next hover tries again`() = runTest {
        val calls = AtomicInteger()
        val cache = TranslationCache(this, { _, _ ->
            if (calls.incrementAndGet() == 1) Err(ServiceError.NetworkError("offline"))
            else Ok("ok") as Result<String, ServiceError>
        })
        assertTrue(cache.translate("word", LanguageCode.ARABIC).isErr)
        assertEquals(Ok("ok"), cache.translate("word", LanguageCode.ARABIC))
        assertEquals(2, calls.get())
    }
}
