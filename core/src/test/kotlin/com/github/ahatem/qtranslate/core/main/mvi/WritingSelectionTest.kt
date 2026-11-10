package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.rewriter.RewriteResponse
import com.github.ahatem.qtranslate.api.summarizer.SummarizeResponse
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Focused tests for Batch-3 writing-selection actions (Replace Selection with Rewrite / Summary).
 *
 * All tests drive the real [MainStore] intent dispatch through [WritingSelectionFixture].
 * Uses runBlocking + Dispatchers.IO (same pattern as OcrPublicationOwnershipTest) so
 * asynchronous provider calls can be scripted deterministically without sleeps.
 * Events are observed through fixture.events (relayed from store.events), never by
 * re-collecting the single-consumer store.events channel.
 */
class WritingSelectionTest {

    // ------------------------------------------------------------------
    // Rewrite
    // ------------------------------------------------------------------

    @Test
    fun `rewrite success emits exactly one replacement event and forwards configured style`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("text to rewrite"))
            fixture.rewriter.started.await()
            fixture.rewriter.release?.complete(Unit)

            val paste = withTimeout(5_000) {
                fixture.awaitEvent { it is MainEvent.PasteTranslation }
            }
            assertEquals("rewritten text", (paste as MainEvent.PasteTranslation).translatedText)
            assertEquals(1, fixture.rewriter.calls.size)
            assertEquals("text to rewrite", fixture.rewriter.calls.single().text)
            assertEquals("FORMAL", fixture.rewriter.calls.single().style)
            assertEquals(1, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `rewrite forwards configured style from settings`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.settings.value = fixture.settings.value.copy(rewriteStyle = "CASUAL")
            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("text"))
            fixture.rewriter.started.await()
            fixture.rewriter.release?.complete(Unit)

            withTimeout(5_000) {
                fixture.awaitEvent { it is MainEvent.PasteTranslation }
            }
            assertEquals("CASUAL", fixture.rewriter.calls.single().style)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `rewrite blank selected text does nothing`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("   "))
            // No provider call should happen
            assertEquals(0, fixture.rewriter.calls.size)
            assertEquals(0, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `rewrite no configured service does not paste`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO, includeRewriter = false)
        try {
            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("text"))
            // The visible no-service failure is the completion signal for this path.
            val status = withTimeout(5_000) {
                fixture.awaitEvent {
                    it is MainEvent.UpdateStatusBar && it.code == StatusCode.NoRewriterActive
                } as MainEvent.UpdateStatusBar
            }
            assertEquals(StatusCode.NoRewriterActive, status.code)
            assertEquals(0, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            withTimeout(5_000) {
                fixture.store.state.first { !it.isReplacingSelection }
            }
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `rewrite provider failure does not paste`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.rewriter.result = Err(ServiceError.ServiceUnavailableError("rewriter down"))
            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("text"))
            fixture.rewriter.started.await()
            fixture.rewriter.release?.complete(Unit)

            withTimeout(5_000) {
                fixture.store.state.first { !it.isReplacingSelection }
            }
            assertEquals(0, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `rewrite blank successful result does not paste`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.rewriter.result = Ok(RewriteResponse(""))
            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("text"))
            fixture.rewriter.started.await()
            fixture.rewriter.release?.complete(Unit)

            withTimeout(5_000) {
                fixture.store.state.first { !it.isReplacingSelection }
            }
            assertEquals(0, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `rewrite unexpected throw does not paste and reports sanitized failure`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.rewriter.throws = true
            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("text"))
            fixture.rewriter.started.await()
            fixture.rewriter.release?.complete(Unit)

            // Loading clearing and event collection are scheduled independently: observing
            // !isReplacingSelection does NOT imply the collector has already received the queued
            // status event. Synchronize on the exact event this test asserts instead.
            val status = withTimeout(5_000) {
                fixture.awaitEvent {
                    it is MainEvent.UpdateStatusBar &&
                        it.code == StatusCode.UnexpectedError("IllegalStateException")
                } as MainEvent.UpdateStatusBar
            }
            assertEquals(StatusCode.UnexpectedError("IllegalStateException"), status.code)
            assertEquals(NotificationType.ERROR, status.type)
            assertTrue(status.isTemporary)
            // Safe class metadata only: never raw exception text, never an internal identifier.
            assertFalse(status.code.toString().contains("provider exploded"))
            assertFalse(status.code.toString().contains("writing_failed"))
            assertEquals(0, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            withTimeout(5_000) {
                fixture.store.state.first { !it.isReplacingSelection }
            }
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    // ------------------------------------------------------------------
    // Summary
    // ------------------------------------------------------------------

    @Test
    fun `summary success emits exactly one replacement event and forwards configured length`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.store.dispatch(MainIntent.ReplaceWithSummary("text to summarize"))
            fixture.summarizer.started.await()
            fixture.summarizer.release?.complete(Unit)

            val paste = withTimeout(5_000) {
                fixture.awaitEvent { it is MainEvent.PasteTranslation }
            }
            assertEquals("summary text", (paste as MainEvent.PasteTranslation).translatedText)
            assertEquals(1, fixture.summarizer.calls.size)
            assertEquals("text to summarize", fixture.summarizer.calls.single().text)
            assertEquals("MEDIUM", fixture.summarizer.calls.single().length)
            assertEquals(1, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `summary forwards configured length from settings`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.settings.value = fixture.settings.value.copy(summaryLength = "LONG")
            fixture.store.dispatch(MainIntent.ReplaceWithSummary("text"))
            fixture.summarizer.started.await()
            fixture.summarizer.release?.complete(Unit)

            withTimeout(5_000) {
                fixture.awaitEvent { it is MainEvent.PasteTranslation }
            }
            assertEquals("LONG", fixture.summarizer.calls.single().length)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `summary blank selected text does nothing`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.store.dispatch(MainIntent.ReplaceWithSummary("   "))
            assertEquals(0, fixture.summarizer.calls.size)
            assertEquals(0, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `summary provider failure does not paste`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.summarizer.result = Err(ServiceError.ServiceUnavailableError("summarizer down"))
            fixture.store.dispatch(MainIntent.ReplaceWithSummary("text"))
            fixture.summarizer.started.await()
            fixture.summarizer.release?.complete(Unit)

            withTimeout(5_000) {
                fixture.store.state.first { !it.isReplacingSelection }
            }
            assertEquals(0, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `summary blank successful result does not paste`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.summarizer.result = Ok(SummarizeResponse(""))
            fixture.store.dispatch(MainIntent.ReplaceWithSummary("text"))
            fixture.summarizer.started.await()
            fixture.summarizer.release?.complete(Unit)

            withTimeout(5_000) {
                fixture.store.state.first { !it.isReplacingSelection }
            }
            assertEquals(0, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    // ------------------------------------------------------------------
    // Shared ownership: Rewrite A starts, Summary B supersedes, A finishes last -> only B publishes
    // ------------------------------------------------------------------

    @Test
    fun `rewrite A starts, summary B supersedes, A finishes last, only B publishes`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            val aRelease = CompletableDeferred<Unit>()
            val bRelease = CompletableDeferred<Unit>()

            fixture.rewriter.release = aRelease
            fixture.summarizer.release = bRelease

            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("text A"))
            withTimeout(5_000) { fixture.rewriter.started.await() }

            // Summary B starts while A is held -> B becomes current
            fixture.store.dispatch(MainIntent.ReplaceWithSummary("text B"))
            withTimeout(5_000) { fixture.summarizer.started.await() }

            // A finishes last (after B is current) -> must NOT publish
            aRelease.complete(Unit)
            // B finishes -> publishes
            bRelease.complete(Unit)

            val paste = withTimeout(5_000) {
                fixture.awaitEvent { it is MainEvent.PasteTranslation }
            }
            assertEquals("summary text", (paste as MainEvent.PasteTranslation).translatedText)
            assertEquals(1, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `summary A starts, rewrite B supersedes, A finishes last, only B publishes`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            val aRelease = CompletableDeferred<Unit>()
            val bRelease = CompletableDeferred<Unit>()

            fixture.summarizer.release = aRelease
            fixture.rewriter.release = bRelease

            fixture.store.dispatch(MainIntent.ReplaceWithSummary("text A"))
            withTimeout(5_000) { fixture.summarizer.started.await() }

            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("text B"))
            withTimeout(5_000) { fixture.rewriter.started.await() }

            aRelease.complete(Unit)
            bRelease.complete(Unit)

            val paste = withTimeout(5_000) {
                fixture.awaitEvent { it is MainEvent.PasteTranslation }
            }
            assertEquals("rewritten text", (paste as MainEvent.PasteTranslation).translatedText)
            assertEquals(1, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    // ------------------------------------------------------------------
    // Loading lifecycle
    // ------------------------------------------------------------------

    @Test
    fun `loading clears on rewrite success`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("text"))
            fixture.rewriter.started.await()
            fixture.rewriter.release?.complete(Unit)
            withTimeout(5_000) {
                fixture.store.state.first { !it.isReplacingSelection }
            }
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `loading clears on rewrite failure`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.rewriter.result = Err(ServiceError.ServiceUnavailableError("down"))
            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("text"))
            fixture.rewriter.started.await()
            fixture.rewriter.release?.complete(Unit)
            withTimeout(5_000) {
                fixture.store.state.first { !it.isReplacingSelection }
            }
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }

    // ------------------------------------------------------------------
    // Non-cooperative stale-publication races
    //
    // These make no cooperative-cancellation assumption: the stale request has already computed
    // its result (or already buffered its paste), and a newer request claims ownership before the
    // UI accepts the stale delivery. Without the delivery-time ownership gate these tests fail.
    // ------------------------------------------------------------------

    @Test
    fun `a buffered rewrite paste is rejected when summary claims before UI accepts it`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO, collectEvents = false)
        try {
            fixture.rewriter.result = Ok(RewriteResponse("rewrite A"))
            fixture.summarizer.result = Ok(SummarizeResponse("summary B"))
            val queued = java.util.concurrent.CountDownLatch(1)
            fixture.store.afterWritingPublication = { queued.countDown() }

            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("A"))
            // A has finished computing and its paste is already buffered in the event channel.
            assertTrue(queued.await(5, TimeUnit.SECONDS), "A never reached its publication attempt")

            // B claims ownership synchronously before any UI delivery is accepted.
            fixture.store.dispatch(MainIntent.ReplaceWithSummary("B"))

            // Only now does the UI start accepting deliveries: the stale buffered paste must lose.
            val accepted = withTimeout(5_000) {
                fixture.store.events.filterIsInstance<MainEvent.PasteTranslation>().first()
            }
            assertEquals("summary B", accepted.translatedText)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a buffered summary paste is rejected when rewrite claims before UI accepts it`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO, collectEvents = false)
        try {
            fixture.summarizer.result = Ok(SummarizeResponse("summary A"))
            fixture.rewriter.result = Ok(RewriteResponse("rewrite B"))
            val queued = java.util.concurrent.CountDownLatch(1)
            fixture.store.afterWritingPublication = { queued.countDown() }

            fixture.store.dispatch(MainIntent.ReplaceWithSummary("A"))
            assertTrue(queued.await(5, TimeUnit.SECONDS), "A never reached its publication attempt")

            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("B"))

            val accepted = withTimeout(5_000) {
                fixture.store.events.filterIsInstance<MainEvent.PasteTranslation>().first()
            }
            assertEquals("rewrite B", accepted.translatedText)
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a rewrite paused before publication cannot publish after summary claims`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.rewriter.result = Ok(RewriteResponse("rewrite A"))
            fixture.summarizer.result = Ok(SummarizeResponse("summary B"))

            val atPublication = java.util.concurrent.CountDownLatch(1)
            val releaseA = java.util.concurrent.Semaphore(0)
            val calls = java.util.concurrent.atomic.AtomicInteger()
            fixture.store.beforeWritingPublication = {
                // Pause only the first (stale) request at the publication boundary.
                if (calls.getAndIncrement() == 0) {
                    atPublication.countDown()
                    releaseA.acquireUninterruptibly()
                }
            }
            val attempts = java.util.concurrent.CountDownLatch(2)
            fixture.store.afterWritingPublication = { attempts.countDown() }

            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("A"))
            assertTrue(atPublication.await(5, TimeUnit.SECONDS), "A never reached the publication boundary")

            // B claims ownership while A is paused there.
            fixture.store.dispatch(MainIntent.ReplaceWithSummary("B"))
            releaseA.release()

            assertTrue(attempts.await(5, TimeUnit.SECONDS), "both publication attempts should have ended")
            val accepted = withTimeout(5_000) {
                fixture.awaitEvent { it is MainEvent.PasteTranslation } as MainEvent.PasteTranslation
            }
            assertEquals("summary B", accepted.translatedText)
            assertEquals(
                emptyList(),
                fixture.events.filterIsInstance<MainEvent.PasteTranslation>()
                    .filter { it.translatedText == "rewrite A" },
                "stale rewrite must never reach paste-back",
            )
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a summary paused before publication cannot publish after rewrite claims`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.summarizer.result = Ok(SummarizeResponse("summary A"))
            fixture.rewriter.result = Ok(RewriteResponse("rewrite B"))

            val atPublication = java.util.concurrent.CountDownLatch(1)
            val releaseA = java.util.concurrent.Semaphore(0)
            val calls = java.util.concurrent.atomic.AtomicInteger()
            fixture.store.beforeWritingPublication = {
                if (calls.getAndIncrement() == 0) {
                    atPublication.countDown()
                    releaseA.acquireUninterruptibly()
                }
            }
            val attempts = java.util.concurrent.CountDownLatch(2)
            fixture.store.afterWritingPublication = { attempts.countDown() }

            fixture.store.dispatch(MainIntent.ReplaceWithSummary("A"))
            assertTrue(atPublication.await(5, TimeUnit.SECONDS), "A never reached the publication boundary")

            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("B"))
            releaseA.release()

            assertTrue(attempts.await(5, TimeUnit.SECONDS), "both publication attempts should have ended")
            val accepted = withTimeout(5_000) {
                fixture.awaitEvent { it is MainEvent.PasteTranslation } as MainEvent.PasteTranslation
            }
            assertEquals("rewrite B", accepted.translatedText)
            assertEquals(
                emptyList(),
                fixture.events.filterIsInstance<MainEvent.PasteTranslation>()
                    .filter { it.translatedText == "summary A" },
                "stale summary must never reach paste-back",
            )
        } finally {
            fixture.cleanUp()
        }
    }

    /**
     * Ownership admission must follow [MainStore.dispatch] order, not the order the scheduler runs
     * the launched bodies in. [LifoDispatcher] runs queued coroutine bodies newest-first, so if the
     * claim were still made inside the launched coroutine (the pre-fix shape) the second intent's
     * body would run first and the first intent would wrongly become the current owner.
     */
    @Test
    fun `ownership follows dispatch order even when coroutine bodies run newest-first`() = runBlocking {
        val dispatcher = LifoDispatcher()
        val fixture = WritingSelectionFixture(dispatcher = dispatcher)
        try {
            fixture.rewriter.result = Ok(RewriteResponse("rewrite A"))
            fixture.summarizer.result = Ok(SummarizeResponse("summary B"))
            // Let fixture startup bodies run and settle first.
            dispatcher.runAllLifo()

            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("A"))
            fixture.store.dispatch(MainIntent.ReplaceWithSummary("B"))

            // The summary body runs before the rewrite body because of the LIFO dispatcher.
            dispatcher.runAllLifo()

            val accepted = withTimeout(5_000) {
                fixture.awaitEvent { it is MainEvent.PasteTranslation } as MainEvent.PasteTranslation
            }
            assertEquals("summary B", accepted.translatedText, "the later dispatch must own publication")
            assertTrue(
                fixture.events.filterIsInstance<MainEvent.PasteTranslation>()
                    .none { it.translatedText == "rewrite A" },
                "the earlier dispatch must not survive reversed scheduling",
            )
        } finally {
            fixture.cleanUp()
        }
    }

    // ------------------------------------------------------------------
    // Unexpected-error presentation
    // ------------------------------------------------------------------

    @Test
    fun `unexpected writing failure surfaces only safe class metadata`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.rewriter.throws = true
            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("text"))
            fixture.rewriter.started.await()

            val status = withTimeout(5_000) {
                fixture.awaitEvent {
                    it is MainEvent.UpdateStatusBar && it.code is StatusCode.UnexpectedError
                } as MainEvent.UpdateStatusBar
            }
            assertEquals(StatusCode.UnexpectedError("IllegalStateException"), status.code)
            assertTrue(fixture.events.none {
                it is MainEvent.UpdateStatusBar && it.code.toString().contains("writing_failed")
            }, "the internal identifier must not be interpolated into user-visible status")
        } finally {
            fixture.cleanUp()
        }
    }

    // ------------------------------------------------------------------
    // Shutdown invalidates writing work
    // ------------------------------------------------------------------

    @Test
    fun `shutdown cancels current writing work so it cannot paste`() = runBlocking {
        val fixture = WritingSelectionFixture(dispatcher = Dispatchers.IO)
        try {
            fixture.rewriter.release = CompletableDeferred()
            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("text"))
            withTimeout(5_000) { fixture.rewriter.started.await() }

            // Shutdown cancels current writing work
            fixture.store.onShutdown()

            // Release the held provider call; the cancelled coroutine must not paste
            fixture.rewriter.release?.complete(Unit)
            // Give the cancelled coroutine a moment to observe cancellation
            withTimeout(5_000) {
                fixture.store.state.first { !it.isReplacingSelection }
            }
            assertEquals(0, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            assertFalse(fixture.store.state.value.isReplacingSelection)
        } finally {
            fixture.cleanUp()
        }
    }
}

/**
 * A deterministic dispatcher that queues every dispatched body and runs them newest-first.
 *
 * Used to prove that writing ownership admission follows [MainStore.dispatch] order rather than
 * coroutine scheduling order: with a pre-fix implementation that claimed ownership inside the
 * launched body, the second intent's body would run first and wrongly take the older token.
 */
private class LifoDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(tasks) { tasks.addLast(block) }
    }

    /** Runs every currently queued body in LIFO order until the queue drains. */
    fun runAllLifo() {
        while (true) {
            val next = synchronized(tasks) { tasks.removeLastOrNull() } ?: return
            next.run()
        }
    }
}
