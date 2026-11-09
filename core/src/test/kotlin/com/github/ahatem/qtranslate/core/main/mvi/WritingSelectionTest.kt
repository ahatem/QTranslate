package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.rewriter.RewriteResponse
import com.github.ahatem.qtranslate.api.summarizer.SummarizeResponse
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            // No provider call should happen; the no-service path returns synchronously
            assertEquals(0, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
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

            withTimeout(5_000) {
                fixture.store.state.first { !it.isReplacingSelection }
            }
            assertEquals(0, fixture.events.filterIsInstance<MainEvent.PasteTranslation>().size)
            assertFalse(fixture.store.state.value.isReplacingSelection)
            // Unexpected error status should be visible (not raw exception text)
            val statusEvents = fixture.events.filterIsInstance<MainEvent.UpdateStatusBar>()
            assertTrue(statusEvents.any { it.code.toString().contains("writing_failed") })
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
