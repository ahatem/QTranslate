package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.main.mvi.MainState
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The main window's state collector is UI work, not background work.
 *
 * Rendering an older snapshot after a newer editor edit rewrites the document with stale text,
 * which is exactly the fast-typing failure in #413. These tests pin the ordering at the
 * observer boundary: collection on Swing cannot deliver a snapshot that a keystroke has
 * already overtaken, while the background collection it replaces demonstrably can.
 */
class MainStateRenderObserverTest {

    private val settings: StateFlow<SettingsState> =
        MutableStateFlow(SettingsState.initial(Configuration.DEFAULT))

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun handler() = CoroutineExceptionHandler { _, _ -> }

    private fun await(latch: CountDownLatch, message: String) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), message)
    }

    /**
     * A newer edit lands while the EDT is busy. Collection on Swing has not read the older
     * snapshot yet, so when it resumes it must render only the newer text. Background
     * collection would already have captured the older snapshot and queue it behind the busy
     * event, overwriting the edit when the queue drains.
     */
    @Test
    fun `render on the EDT never applies a snapshot a newer edit has overtaken`() {
        val main = MutableStateFlow(MainState(inputText = "seed"))
        val rendered = mutableListOf<String>()
        val seedRendered = CountDownLatch(1)
        val latestRendered = CountDownLatch(1)
        val scope = scope()

        val observer = MainStateRenderObserver(scope, main, settings)
        val job = observer.start(handler()) { state, _ ->
            assertTrue(
                SwingUtilities.isEventDispatchThread(),
                "main-window rendering must run on the EDT",
            )
            rendered += state.inputText
            when (state.inputText) {
                "seed" -> seedRendered.countDown()
                "ab" -> latestRendered.countDown()
            }
        }

        try {
            await(seedRendered, "the initial snapshot was not rendered")

            val edtBlocked = CountDownLatch(1)
            val releaseEdt = CountDownLatch(1)
            SwingUtilities.invokeLater {
                edtBlocked.countDown()
                releaseEdt.await(5, TimeUnit.SECONDS)
            }
            await(edtBlocked, "the EDT did not enter the blocking event")

            // Both updates are made while the EDT is busy, as a keystroke and the translation
            // state that follows it would be. Only the latest value is current when the EDT
            // resumes.
            main.value = MainState(inputText = "a")
            main.value = MainState(inputText = "ab")
            releaseEdt.countDown()

            await(latestRendered, "the newer snapshot was not rendered")
            assertEquals(
                listOf("seed", "ab"),
                rendered,
                "the observer must not render a snapshot overtaken by a newer edit",
            )
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    /**
     * The old arrangement, kept as a control: background collection captures the older
     * snapshot before the edit and then applies it after the editor already holds newer text.
     * This is the concrete document corruption the Swing dispatcher above prevents.
     */
    @Test
    fun `background collection reproduces the stale echo the EDT dispatcher prevents`() {
        val main = MutableStateFlow(MainState(inputText = "seed"))
        val rendered = Collections.synchronizedList(mutableListOf<String>())
        val editorText = AtomicReference("")
        val staleOverwrite = AtomicBoolean(false)
        val seedRendered = CountDownLatch(1)
        val staleRenderStarted = CountDownLatch(1)
        val staleRenderEvaluated = CountDownLatch(1)
        val releaseStaleRender = CountDownLatch(1)
        val latestRendered = CountDownLatch(1)
        val scope = scope()

        val observer = MainStateRenderObserver(
            scope = scope,
            mainState = main,
            settingsState = settings,
            renderDispatcher = Dispatchers.Default,
        )
        val job = observer.start(handler()) { state, _ ->
            rendered += state.inputText
            when (state.inputText) {
                "seed" -> seedRendered.countDown()
                "a" -> {
                    // Hold the stale render so the newer edit can land first.
                    staleRenderStarted.countDown()
                    releaseStaleRender.await(5, TimeUnit.SECONDS)
                    if (editorText.get() == "ab") staleOverwrite.set(true)
                    // Rendering "ab" may finish on another thread before this callback resumes,
                    // so the control is only established once this line has run.
                    staleRenderEvaluated.countDown()
                }
                "ab" -> latestRendered.countDown()
            }
            editorText.set(state.inputText)
        }

        try {
            await(seedRendered, "the initial snapshot was not rendered")

            main.value = MainState(inputText = "a")
            await(staleRenderStarted, "the stale snapshot was not collected")

            // The user's next edit and its state update happen while the older render is in
            // flight. The pane already contains "ab" when the stale callback resumes.
            editorText.set("ab")
            main.value = MainState(inputText = "ab")
            releaseStaleRender.countDown()

            await(staleRenderEvaluated, "the stale render never resumed after the edit landed")
            await(latestRendered, "the newer snapshot was not rendered")
            assertTrue(
                staleOverwrite.get(),
                "the control did not reproduce the stale overwrite; it no longer proves anything",
            )
            assertEquals(listOf("seed", "a", "ab"), rendered)
            assertEquals(
                "ab",
                editorText.get(),
                "the final state must still be the newer edit",
            )
        } finally {
            job.cancel()
            scope.cancel()
        }
    }
}
