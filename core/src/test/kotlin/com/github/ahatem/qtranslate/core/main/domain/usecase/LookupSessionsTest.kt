package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.api.imagesearch.ImageResult
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.ConcurrentSessionStarts
import com.github.ahatem.qtranslate.core.shared.StatusCode
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
class LookupSessionsTest {
    @Test fun `dictionary cancellation overlapping start cannot lose the dispatched job`() = runTest {
        val dispatcher = ConcurrentSessionStarts(StandardTestDispatcher(testScheduler))
        val cancelled = CompletableDeferred<Unit>()
        val session = DictionaryLookupSession(CoroutineScope(coroutineContext + dispatcher), { _, started ->
            started()
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }, { _, _ -> }, { _, _ -> error("cancelled dictionary published") })
        dispatcher.overlap({ session.start(DictionaryLookupRequest("word", LanguageCode.ENGLISH, null)) },
            session::cancel, { runCurrent() })
        runCurrent()
        assertTrue(cancelled.isCompleted)
    }

    @Test fun `image cancellation overlapping start cannot lose the dispatched job`() = runTest {
        val dispatcher = ConcurrentSessionStarts(StandardTestDispatcher(testScheduler))
        val cancelled = CompletableDeferred<Unit>()
        val session = ImageLookupSession(CoroutineScope(coroutineContext + dispatcher), { _, started ->
            started()
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }, { _, _ -> }, { _, _ -> error("cancelled images published") })
        dispatcher.overlap({ session.start(ImageLookupRequest("term", LanguageCode.ENGLISH)) },
            session::cancel, { runCurrent() })
        runCurrent()
        assertTrue(cancelled.isCompleted)
    }

    @Test fun `overlapping dictionary starts have distinct publication rights and retain current job`() = runTest {
        val dispatcher = ConcurrentSessionStarts(StandardTestDispatcher(testScheduler))
        val releaseOld = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<String>()
        val executed = mutableListOf<String>()
        val visible = mutableListOf<String>()
        val publications = mutableMapOf<String, (() -> Unit) -> Unit>()
        val session = DictionaryLookupSession(CoroutineScope(coroutineContext + dispatcher), { request, started ->
            executed += request.word
            started()
            if (request.word == "old") withContext(NonCancellable) { releaseOld.await() }
            else try { awaitCancellation() } finally { cancelled += request.word }
            DictionaryLookupOutcome(StatusCode.DictionaryReady, NotificationType.INFO,
                listOf(DictionaryEntry(request.word, "", emptyList())), false)
        }, { request, publish -> publications[request.word] = publish }, { outcome, publish ->
            publish { visible += outcome.entries!!.single().word }
        })
        fun start(word: String) = session.start(DictionaryLookupRequest(word, LanguageCode.ENGLISH, null))
        start("old"); runCurrent()
        dispatcher.overlap({ start("contender") }, { start("current") }, { runCurrent() }); runCurrent()
        assertEquals(listOf("old", "contender", "current"), executed)
        publications.getValue("old").invoke { visible += "stale" }
        publications.getValue("contender").invoke { visible += "collision" }
        publications.getValue("current").invoke { visible += "current" }
        assertEquals(listOf("current"), visible, "concurrent starts must never share publication rights")
        releaseOld.complete(Unit); runCurrent()
        assertEquals(listOf("current"), visible, "stale outcome must stay suppressed")
        session.cancel(); runCurrent()
        assertEquals(listOf("contender", "current"), cancelled, "old finally must not clear the current job")
        publications.getValue("current").invoke { visible += "cancelled" }
        assertEquals(listOf("current"), visible)
    }

    @Test fun `overlapping image starts have distinct publication rights and retain current job`() = runTest {
        val dispatcher = ConcurrentSessionStarts(StandardTestDispatcher(testScheduler))
        val releaseOld = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<String>()
        val executed = mutableListOf<String>()
        val visible = mutableListOf<String>()
        val publications = mutableMapOf<String, (() -> Unit) -> Unit>()
        val session = ImageLookupSession(CoroutineScope(coroutineContext + dispatcher), { request, started ->
            executed += request.term
            started()
            if (request.term == "old") withContext(NonCancellable) { releaseOld.await() }
            else try { awaitCancellation() } finally { cancelled += request.term }
            ImageLookupOutcome(StatusCode.ImageSearchReady, NotificationType.INFO,
                listOf(ImageResult(request.term, request.term)), false)
        }, { request, publish -> publications[request.term] = publish }, { outcome, publish ->
            publish { visible += outcome.results!!.single().thumbnailUrl }
        })
        fun start(term: String) = session.start(ImageLookupRequest(term, LanguageCode.ENGLISH))
        start("old"); runCurrent()
        dispatcher.overlap({ start("contender") }, { start("current") }, { runCurrent() }); runCurrent()
        assertEquals(listOf("old", "contender", "current"), executed)
        publications.getValue("old").invoke { visible += "stale" }
        publications.getValue("contender").invoke { visible += "collision" }
        publications.getValue("current").invoke { visible += "current" }
        assertEquals(listOf("current"), visible, "concurrent starts must never share publication rights")
        releaseOld.complete(Unit); runCurrent()
        assertEquals(listOf("current"), visible)
        session.cancel(); runCurrent()
        assertEquals(listOf("contender", "current"), cancelled, "old finally must not clear the current job")
        publications.getValue("current").invoke { visible += "cancelled" }
        assertEquals(listOf("current"), visible)
    }

    @Test fun `dictionary retains old results while loading and suppresses superseded completion`() = runTest {
        val releases = listOf(CompletableDeferred<Unit>(), CompletableDeferred())
        var count = 0
        var visible = listOf(DictionaryEntry("existing", "", emptyList()))
        val started = mutableListOf<String>()
        val session = DictionaryLookupSession(this, { request, onStarted ->
            val index = count++
            onStarted()
            withContext(NonCancellable) { releases[index].await() }
            DictionaryLookupOutcome(StatusCode.DictionaryReady, NotificationType.INFO,
                listOf(DictionaryEntry(request.word, "", emptyList())), false)
        }, { request, publish -> publish { started += request.word } },
            { outcome, publish -> publish { visible = outcome.entries!! } })
        session.start(DictionaryLookupRequest("old", LanguageCode.ENGLISH, null)); runCurrent()
        assertEquals("existing", visible.single().word)
        session.start(DictionaryLookupRequest("new", LanguageCode.ENGLISH, null)); runCurrent()
        releases[0].complete(Unit); runCurrent()
        assertEquals("existing", visible.single().word)
        releases[1].complete(Unit); runCurrent()
        assertEquals("new", visible.single().word)
        assertEquals(listOf("old", "new"), started)
    }

    @Test fun `image lookup retains old results and cancel suppresses late completion`() = runTest {
        val releases = listOf(CompletableDeferred<Unit>(), CompletableDeferred(), CompletableDeferred())
        var count = 0
        var visible = listOf(ImageResult("existing", "existing"))
        val session = ImageLookupSession(this, { request, onStarted ->
            val index = count++
            onStarted()
            withContext(NonCancellable) { releases[index].await() }
            ImageLookupOutcome(StatusCode.ImageSearchReady, NotificationType.INFO,
                listOf(ImageResult(request.term, request.term)), false)
        }, { _, _ -> }, { outcome, publish -> publish { visible = outcome.results!! } })
        session.start(ImageLookupRequest("old", LanguageCode.ENGLISH)); runCurrent()
        session.start(ImageLookupRequest("new", LanguageCode.ENGLISH)); runCurrent()
        releases[0].complete(Unit); runCurrent()
        assertEquals("existing", visible.single().thumbnailUrl)
        releases[1].complete(Unit); runCurrent()
        assertEquals("new", visible.single().thumbnailUrl)
        session.start(ImageLookupRequest("cancelled", LanguageCode.ENGLISH)); runCurrent()
        session.cancel(); releases[2].complete(Unit); runCurrent()
        assertEquals("new", visible.single().thumbnailUrl)
    }
}
