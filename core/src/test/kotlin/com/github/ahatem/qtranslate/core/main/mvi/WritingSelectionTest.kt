package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.shared.StatusCode
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
import kotlin.test.assertTrue

/**
 * Replace Selection with Rewrite / Summary through the real [MainStore]. Rewrite and Summary share
 * one ownership model, so each behaviour is checked for both writers.
 *
 * Runs on real dispatchers with latches instead of sleeps. Events are read through
 * fixture.events, never by re-collecting the single-consumer store.events channel.
 */
class WritingSelectionTest {

    private enum class Writer(val successText: String) {
        REWRITE("rewritten text"),
        SUMMARY("summary text");

        val other: Writer get() = if (this == REWRITE) SUMMARY else REWRITE

        fun intent(text: String): MainIntent = when (this) {
            REWRITE -> MainIntent.ReplaceWithRewrite(text)
            SUMMARY -> MainIntent.ReplaceWithSummary(text)
        }

        fun service(fixture: WritingSelectionFixture): ScriptedWriter<*, *> = when (this) {
            REWRITE -> fixture.rewriter
            SUMMARY -> fixture.summarizer
        }

        /** The one setting this writer forwards to its provider, as the provider saw it. */
        fun forwardedSetting(fixture: WritingSelectionFixture): String? = when (this) {
            REWRITE -> fixture.rewriter.calls.single().style
            SUMMARY -> fixture.summarizer.calls.single().length
        }

        fun configure(fixture: WritingSelectionFixture, value: String) {
            fixture.settings.value = when (this) {
                REWRITE -> fixture.settings.value.copy(rewriteStyle = value)
                SUMMARY -> fixture.settings.value.copy(summaryLength = value)
            }
        }
    }

    private val ordered = Writer.entries.map { it to it.other }

    private fun fixture(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        collectEvents: Boolean = true,
    ) = WritingSelectionFixture(dispatcher = dispatcher, collectEvents = collectEvents)

    private fun WritingSelectionFixture.pastes() = events.filterIsInstance<MainEvent.PasteTranslation>()

    private suspend fun WritingSelectionFixture.awaitPaste(): MainEvent.PasteTranslation =
        withTimeout(5_000) { awaitEvent { it is MainEvent.PasteTranslation } } as MainEvent.PasteTranslation

    /** Delivery of the paste does not imply loading was released yet, so wait for the state itself. */
    private suspend fun WritingSelectionFixture.awaitLoadingCleared() {
        withTimeout(5_000) { store.state.first { !it.isReplacingSelection } }
        assertFalse(store.state.value.isReplacingSelection)
    }

    private suspend fun Writer.dispatchAndRelease(fixture: WritingSelectionFixture, text: String) {
        fixture.store.dispatch(intent(text))
        service(fixture).started.await()
        service(fixture).release?.complete(Unit)
    }

    @Test
    fun `success emits exactly one replacement event and forwards the default setting`() = runBlocking {
        val defaults = mapOf(Writer.REWRITE to "FORMAL", Writer.SUMMARY to "MEDIUM")
        for (writer in Writer.entries) {
            val fixture = fixture()
            try {
                writer.dispatchAndRelease(fixture, "input text")

                assertEquals(writer.successText, fixture.awaitPaste().translatedText, writer.name)
                assertEquals(1, writer.service(fixture).calls.size, writer.name)
                assertEquals(defaults[writer], writer.forwardedSetting(fixture), writer.name)
                assertEquals(1, fixture.pastes().size, writer.name)
                fixture.awaitLoadingCleared()
            } finally {
                fixture.cleanUp()
            }
        }
    }

    @Test
    fun `the configured setting is forwarded from settings`() = runBlocking {
        val custom = mapOf(Writer.REWRITE to "CASUAL", Writer.SUMMARY to "LONG")
        for (writer in Writer.entries) {
            val fixture = fixture()
            try {
                writer.configure(fixture, custom.getValue(writer))
                writer.dispatchAndRelease(fixture, "text")
                fixture.awaitPaste()

                assertEquals(custom[writer], writer.forwardedSetting(fixture), writer.name)
            } finally {
                fixture.cleanUp()
            }
        }
    }

    @Test
    fun `blank selected text does nothing`() = runBlocking {
        for (writer in Writer.entries) {
            val fixture = fixture()
            try {
                fixture.store.dispatch(writer.intent("   "))

                assertEquals(0, writer.service(fixture).calls.size, writer.name)
                assertEquals(0, fixture.pastes().size, writer.name)
                assertFalse(fixture.store.state.value.isReplacingSelection, writer.name)
            } finally {
                fixture.cleanUp()
            }
        }
    }

    @Test
    fun `no configured service does not paste`() = runBlocking {
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
            assertEquals(0, fixture.pastes().size)
            fixture.awaitLoadingCleared()
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `a provider failure or blank result does not paste and releases loading`() = runBlocking {
        val outcomes = listOf<Pair<String, (ScriptedWriter<*, *>) -> Unit>>(
            "provider failure" to { it.failWith(ServiceError.ServiceUnavailableError("down")) },
            "blank result" to { it.answerWith("") },
        )
        for (writer in Writer.entries) {
            for ((label, script) in outcomes) {
                val fixture = fixture()
                try {
                    script(writer.service(fixture))
                    writer.dispatchAndRelease(fixture, "text")

                    fixture.awaitLoadingCleared()
                    assertEquals(0, fixture.pastes().size, "${writer.name}: $label")
                } finally {
                    fixture.cleanUp()
                }
            }
        }
    }

    @Test
    fun `an unexpected provider throw reports only safe class metadata`() = runBlocking {
        for (writer in Writer.entries) {
            val fixture = fixture()
            try {
                writer.service(fixture).throws = true
                writer.dispatchAndRelease(fixture, "text")

                // Loading clearing and event collection are scheduled independently, so wait for
                // the exact event asserted on.
                val status = withTimeout(5_000) {
                    fixture.awaitEvent {
                        it is MainEvent.UpdateStatusBar && it.code == StatusCode.UnexpectedError("IllegalStateException")
                    } as MainEvent.UpdateStatusBar
                }
                assertEquals(NotificationType.ERROR, status.type, writer.name)
                assertTrue(status.isTemporary, writer.name)
                assertFalse(status.code.toString().contains("provider exploded"), "raw exception text, ${writer.name}")
                assertFalse(status.code.toString().contains("writing_failed"), "internal identifier, ${writer.name}")
                assertEquals(0, fixture.pastes().size, writer.name)
                fixture.awaitLoadingCleared()
            } finally {
                fixture.cleanUp()
            }
        }
    }

    @Test
    fun `a later writer supersedes an earlier one that finishes last, and only the later one publishes`() = runBlocking {
        for ((first, second) in ordered) {
            val fixture = fixture()
            try {
                val firstRelease = CompletableDeferred<Unit>()
                val secondRelease = CompletableDeferred<Unit>()
                first.service(fixture).release = firstRelease
                second.service(fixture).release = secondRelease

                fixture.store.dispatch(first.intent("text A"))
                withTimeout(5_000) { first.service(fixture).started.await() }
                fixture.store.dispatch(second.intent("text B"))
                withTimeout(5_000) { second.service(fixture).started.await() }

                firstRelease.complete(Unit)
                secondRelease.complete(Unit)

                assertEquals(second.successText, fixture.awaitPaste().translatedText, "$first then $second")
                assertEquals(1, fixture.pastes().size, "$first then $second")
                fixture.awaitLoadingCleared()
            } finally {
                fixture.cleanUp()
            }
        }
    }

    // Non-cooperative races: the stale request already computed its result, so only the
    // delivery-time ownership gate can stop it.

    @Test
    fun `a buffered paste is rejected when the other writer claims before the UI accepts it`() = runBlocking {
        for ((first, second) in ordered) {
            val fixture = fixture(collectEvents = false)
            try {
                first.service(fixture).answerWith("${first.name} A")
                second.service(fixture).answerWith("${second.name} B")
                val queued = CountDownLatch(1)
                fixture.store.afterWritingPublication = { queued.countDown() }

                fixture.store.dispatch(first.intent("A"))
                assertTrue(queued.await(5, TimeUnit.SECONDS), "A never reached its publication attempt")
                fixture.store.dispatch(second.intent("B"))

                // Only now does the UI start accepting deliveries: the stale buffered paste must lose.
                val accepted = withTimeout(5_000) {
                    fixture.store.events.filterIsInstance<MainEvent.PasteTranslation>().first()
                }
                assertEquals("${second.name} B", accepted.translatedText, "$first then $second")
            } finally {
                fixture.cleanUp()
            }
        }
    }

    @Test
    fun `a writer paused before publication cannot publish after the other writer claims`() = runBlocking {
        for ((first, second) in ordered) {
            val fixture = fixture()
            try {
                first.service(fixture).answerWith("${first.name} A")
                second.service(fixture).answerWith("${second.name} B")

                val atPublication = CountDownLatch(1)
                val releaseA = Semaphore(0)
                val calls = AtomicInteger()
                fixture.store.beforeWritingPublication = {
                    // Pause only the first (stale) request at the publication boundary.
                    if (calls.getAndIncrement() == 0) {
                        atPublication.countDown()
                        releaseA.acquireUninterruptibly()
                    }
                }
                val attempts = CountDownLatch(2)
                fixture.store.afterWritingPublication = { attempts.countDown() }

                fixture.store.dispatch(first.intent("A"))
                assertTrue(atPublication.await(5, TimeUnit.SECONDS), "A never reached the publication boundary")
                fixture.store.dispatch(second.intent("B"))
                releaseA.release()

                assertTrue(attempts.await(5, TimeUnit.SECONDS), "both publication attempts should have ended")
                assertEquals("${second.name} B", fixture.awaitPaste().translatedText, "$first then $second")
                assertEquals(
                    emptyList(),
                    fixture.pastes().filter { it.translatedText == "${first.name} A" },
                    "stale ${first.name} must never reach paste-back",
                )
            } finally {
                fixture.cleanUp()
            }
        }
    }

    /**
     * Ownership admission must follow [MainStore.dispatch] order, not the order the scheduler runs
     * the launched bodies in. [LifoDispatcher] runs queued bodies newest-first, so claiming inside
     * the launched coroutine would make the first intent the current owner.
     */
    @Test
    fun `ownership follows dispatch order even when coroutine bodies run newest-first`() = runBlocking {
        val dispatcher = LifoDispatcher()
        val fixture = fixture(dispatcher = dispatcher)
        try {
            fixture.rewriter.answerWith("rewrite A")
            fixture.summarizer.answerWith("summary B")
            // Let fixture startup bodies run and settle first.
            dispatcher.runAllLifo()

            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("A"))
            fixture.store.dispatch(MainIntent.ReplaceWithSummary("B"))
            dispatcher.runAllLifo()

            assertEquals("summary B", fixture.awaitPaste().translatedText, "the later dispatch must own publication")
            assertTrue(
                fixture.pastes().none { it.translatedText == "rewrite A" },
                "the earlier dispatch must not survive reversed scheduling",
            )
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun `shutdown cancels current writing work so it cannot paste`() = runBlocking {
        val fixture = fixture()
        try {
            fixture.rewriter.release = CompletableDeferred()
            fixture.store.dispatch(MainIntent.ReplaceWithRewrite("text"))
            withTimeout(5_000) { fixture.rewriter.started.await() }

            fixture.store.onShutdown()
            // The cancelled coroutine must not paste once the held provider call is released.
            fixture.rewriter.release?.complete(Unit)

            fixture.awaitLoadingCleared()
            assertEquals(0, fixture.pastes().size)
        } finally {
            fixture.cleanUp()
        }
    }
}

/** Queues every dispatched body and runs them newest-first, so scheduling order is the reverse of dispatch order. */
private class LifoDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(tasks) { tasks.addLast(block) }
    }

    fun runAllLifo() {
        while (true) {
            val next = synchronized(tasks) { tasks.removeLastOrNull() } ?: return
            next.run()
        }
    }
}
