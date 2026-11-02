package com.github.ahatem.qtranslate.core.document

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.ConcurrentSessionStarts
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentTranslationSessionTest {
    private fun request(name: String) = DocumentTranslationRequest(
        File("$name.txt"), File("$name-out.txt"), LanguageCode("en"), LanguageCode("fr")
    )

    @Test fun `overlapping starts retain the newest job after stale cleanup`() = runTest {
        val dispatcher = ConcurrentSessionStarts(StandardTestDispatcher(testScheduler))
        val releaseOld = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<String>()
        val executed = mutableListOf<String>()
        val updates = mutableListOf<DocumentTranslationProgress?>()
        val completed = mutableListOf<File>()
        val session = DocumentTranslationSession(CoroutineScope(coroutineContext + dispatcher), { request, progress ->
            val name = request.inputFile.nameWithoutExtension
            executed += name
            if (name == "old") withContext(NonCancellable) {
                releaseOld.await()
                progress(DocumentTranslationProgress(9, 9))
                request.outputFile
            } else try { awaitCancellation() } finally { cancelled += name }
        }, updates::add, { output, publish -> publish { completed += output } }, { message, publish -> publish { error(message) } })

        session.start(request("old")); runCurrent()
        dispatcher.overlap({ session.start(request("contender")) }, { session.start(request("current")) }, { runCurrent() })
        runCurrent()
        assertEquals(listOf("old", "contender", "current"), executed)
        releaseOld.complete(Unit); runCurrent()
        assertEquals(emptyList(), updates, "old generation must not publish progress or cleanup")
        assertEquals(emptyList(), completed)
        session.cancel(); runCurrent()
        assertEquals(listOf("contender", "current"), cancelled, "stale cleanup or a late start must not lose the owned job")
        assertEquals(listOf<DocumentTranslationProgress?>(null), updates)
    }

    @Test fun `replacement during final progress suppresses old completion`() = runTest {
        val completed = mutableListOf<File>()
        lateinit var session: DocumentTranslationSession
        var replace = true
        session = DocumentTranslationSession(this, { request, _ -> request.outputFile }, {
            if (it == null && replace) {
                replace = false
                session.start(request("new"))
            }
        }, { output, publish -> publish { completed += output } }, { message, publish -> publish { error(message) } })
        session.start(request("old")); runCurrent()
        assertEquals(listOf(File("new-out.txt")), completed)
    }

    @Test fun `cancel overlapping start owns and cancels the dispatched job`() = runTest {
        val dispatcher = ConcurrentSessionStarts(StandardTestDispatcher(testScheduler))
        val cancelled = CompletableDeferred<Unit>()
        val session = DocumentTranslationSession(CoroutineScope(coroutineContext + dispatcher), { _, _ ->
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }, {}, { _, _ -> error("cancelled document completed") }, { _, _ -> error("cancelled document failed") })
        dispatcher.overlap({ session.start(request("cancel")) }, session::cancel, { runCurrent() })
        runCurrent()
        assertTrue(cancelled.isCompleted)
    }

    @Test fun `suspended completion and failure callbacks cannot publish after replacement`() = runTest {
        for (fail in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val callback: suspend (String, (() -> Unit) -> Unit) -> Unit = { value, publish ->
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    publish { events += value }
                }
            }
            val session = DocumentTranslationSession(this, { request, _ ->
                if (request.inputFile.nameWithoutExtension == "new") awaitCancellation()
                if (fail) throw DocumentTranslationException("expected failure")
                request.outputFile
            }, {}, { file, publish -> callback(file.name, publish) }, callback)
            session.start(request("old")); runCurrent()
            assertTrue(entered.isCompleted)
            session.start(request("new")); runCurrent()
            release.complete(Unit); runCurrent()
            assertEquals(emptyList(), events)
            session.cancel(); runCurrent()
        }
    }

    @Test fun `superseded job cannot emit progress or completion`() = runTest {
        val releases = listOf(CompletableDeferred<Unit>(), CompletableDeferred())
        val updates = mutableListOf<DocumentTranslationProgress?>()
        val completed = mutableListOf<File>()
        var calls = 0
        val session = DocumentTranslationSession(this, { request, progress ->
            val index = calls++
            withContext(NonCancellable) {
                releases[index].await()
                progress(DocumentTranslationProgress(index + 1, 2))
                request.outputFile
            }
        }, updates::add, { output, publish -> publish { completed += output } }, { message, publish -> publish { error(message) } })

        session.start(request("old"))
        runCurrent()
        session.start(request("new"))
        runCurrent()
        releases[0].complete(Unit)
        runCurrent()
        assertEquals(emptyList(), updates)
        assertEquals(emptyList(), completed)
        releases[1].complete(Unit)
        runCurrent()
        assertEquals(listOf(DocumentTranslationProgress(2, 2), null), updates)
        assertEquals(listOf(File("new-out.txt")), completed)
    }

    @Test fun `cancel clears progress and suppresses late updates`() = runTest {
        val release = CompletableDeferred<Unit>()
        val updates = mutableListOf<DocumentTranslationProgress?>()
        val completed = mutableListOf<File>()
        val session = DocumentTranslationSession(this, { request, progress ->
            progress(DocumentTranslationProgress(0, 1))
            withContext(NonCancellable) {
                release.await()
                progress(DocumentTranslationProgress(1, 1))
                request.outputFile
            }
        }, updates::add, { output, publish -> publish { completed += output } }, { message, publish -> publish { error(message) } })
        session.start(request("cancel"))
        runCurrent()
        session.cancel()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(DocumentTranslationProgress(0, 1), null), updates)
        assertEquals(emptyList(), completed)
    }
}
