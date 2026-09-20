package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.HotkeyAction
import com.github.ahatem.qtranslate.core.settings.data.HotkeyBinding
import com.github.ahatem.qtranslate.core.settings.data.HotkeyScope
import com.github.ahatem.qtranslate.ui.swing.main.input.CopyInjector
import com.github.ahatem.qtranslate.ui.swing.main.input.FakeGlobalInputBackend
import com.github.ahatem.qtranslate.ui.swing.main.input.GlobalInputEvent
import com.github.ahatem.qtranslate.ui.swing.main.input.InputRuntimeState
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.FakeChangeMonitor
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingClipboard
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.RecordingLogger
import com.github.ahatem.qtranslate.ui.swing.shared.clipboard.SelectionCapture
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Real-thread concurrency tests for [MainGlobalKeyListener]'s reconciliation transaction.
 *
 * The existing [MainGlobalKeyListenerTest] suite runs everything through a single-threaded
 * `runTest` coroutine, which cannot exercise genuine cross-thread interleaving. In the real
 * application, [MainGlobalKeyListener.updateRuntimeState] is driven both from the settings state
 * flow on `Dispatchers.Default` and from the Swing EDT (`setPaused`, the initial state push), so
 * these tests use real [Thread]s and [MainGlobalKeyListener.hasQueuedReconcile] (backed by a
 * [java.util.concurrent.locks.ReentrantLock]) to force and observe overlap deterministically,
 * never a sleep to hope a race resolves a particular way.
 */
class MainGlobalKeyListenerConcurrencyTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun global(action: HotkeyAction, keyCode: Int, modifiers: Int) =
        HotkeyBinding(action, keyCode = keyCode, modifiers = modifiers, scope = HotkeyScope.GLOBAL)

    private fun newListener(
        backend: FakeGlobalInputBackend,
        onOpenSnippingTool: () -> Unit = {},
    ): MainGlobalKeyListener {
        val listener = MainGlobalKeyListener(
            scope = scope,
            logger = RecordingLogger(),
            onShowApp = {}, onShowQuickTranslate = {}, onListenToText = {},
            onOpenSnippingTool = onOpenSnippingTool, onReplaceWithTranslation = {},
            onCycleTargetLanguage = {},
            backendFactory = { backend },
            selectionCaptureFactory = { _, _, simulateCopy, logger ->
                SelectionCapture(RecordingClipboard("original"), FakeChangeMonitor(), simulateCopy, logger)
            }
        )
        listener.copyInjector = CopyInjector { true }
        return listener
    }

    /**
     * Blocks the first call to `applyHotkeys` inside the fake backend until told to continue,
     * signalling [entered] the moment it does. This is what lets a test pause one reconciliation
     * mid-transaction (after the native apply, before `accept`/`lastApplied`/`recordApplyOutcome`)
     * so a second one can be started and observed racing against it.
     */
    private fun FakeGlobalInputBackend.pauseFirstApply(): Pair<CountDownLatch, CountDownLatch> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var seen = false
        emitDuringApply = {
            if (!seen) {
                seen = true
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS), "test never released the paused apply")
            }
        }
        return entered to release
    }

    /** Mirror of [pauseFirstApply] for `close()`, so shutdown itself can be paused mid-transaction. */
    private fun FakeGlobalInputBackend.pauseClose(): Pair<CountDownLatch, CountDownLatch> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        onClose = {
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS), "test never released the paused close")
        }
        return entered to release
    }

    /**
     * Waits, without sleeping, until a thread is genuinely queued behind [listener]'s reconcile
     * lock. Spins on the lock's own queued-thread state (a real, observable fact) rather than a
     * fixed delay, bounded only so a broken lock fails the test instead of hanging it forever.
     */
    private fun awaitQueuedReconcile(listener: MainGlobalKeyListener) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!listener.hasQueuedReconcile()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("no thread became queued behind the reconcile lock in time")
            }
            Thread.yield()
        }
    }

    @Test
    fun `two replacement reconciliations forced to overlap serialize and finish consistent`() {
        var ocrCalls = 0
        val backend = FakeGlobalInputBackend()
        val listener = newListener(backend) { ocrCalls++ }
        listener.updateRuntimeState(
            InputRuntimeState(bindings = listOf(global(HotkeyAction.OPEN_OCR, KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK)))
        )
        listener.initialize()
        val baselineToken = listener.acceptedTokenFor(HotkeyAction.OPEN_OCR)
        assertNotNull(baselineToken, "baseline registration must be accepted before the race")

        val (entered, release) = backend.pauseFirstApply()

        // Reconcile A: replace ctrl+I with ctrl+F9.
        val threadA = Thread({
            listener.updateRuntimeState(
                InputRuntimeState(bindings = listOf(global(HotkeyAction.OPEN_OCR, KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK)))
            )
        }, "reconcile-A")
        threadA.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS), "reconcile A must reach applyHotkeys")

        // Reconcile B: a second, different replacement, started while A is paused mid-transaction
        // (native apply done, accept/lastApplied/recordApplyOutcome not yet run).
        val threadB = Thread({
            listener.updateRuntimeState(
                InputRuntimeState(bindings = listOf(global(HotkeyAction.OPEN_OCR, KeyEvent.VK_F10, InputEvent.CTRL_DOWN_MASK)))
            )
        }, "reconcile-B")
        threadB.start()

        // Proof the fix serializes them: B must be blocked behind the lock while A still holds
        // it, observed directly rather than inferred from timing.
        awaitQueuedReconcile(listener)
        // Only the baseline apply and A's own apply have happened so far: B has not reached
        // applyHotkeys at all, because it cannot even plan its tokens until A releases the lock.
        assertEquals(2, backend.applied.size, "B must not have applied anything while queued")

        release.countDown()
        threadA.join(10_000)
        threadB.join(10_000)
        assertFalse(threadA.isAlive)
        assertFalse(threadB.isAlive)

        // A's own replacement token (minted for its apply, the second entry in `applied`) is the
        // "older reconciliation" here: it must never be the one left accepted, and it must never
        // dispatch; it was superseded by B before either one committed.
        val aToken = backend.applied[1].single().id
        assertNotEquals(baselineToken, aToken)

        assertEquals(3, backend.applied.size, "B's apply must have run after being released")
        val lastNative = backend.lastApplied().single()
        assertEquals("control+F10", lastNative.accelerator, "the native set must reflect B, not A")

        val finalToken = listener.acceptedTokenFor(HotkeyAction.OPEN_OCR)
        assertNotNull(finalToken)
        assertEquals(
            lastNative.id,
            finalToken,
            "the accepted token must describe exactly the native set that is actually installed"
        )
        assertNotEquals(aToken, finalToken, "A's own token must not be the one left accepted")

        // A's superseded token must not dispatch even though it was minted and briefly the
        // subject of an in-flight (if never accepted) transaction.
        backend.emit(GlobalInputEvent.Hotkey(aToken))
        assertEquals(0, ocrCalls, "a superseded token must never dispatch")
    }

    @Test
    fun `token identities are never duplicated under concurrent reconciliation`() {
        val backend = FakeGlobalInputBackend()
        val listener = newListener(backend)
        listener.initialize()

        val threadCount = 8
        val ready = CountDownLatch(threadCount)
        val go = CountDownLatch(1)
        val threads = (0 until threadCount).map { index ->
            Thread({
                ready.countDown()
                assertTrue(go.await(10, TimeUnit.SECONDS))
                listener.updateRuntimeState(
                    InputRuntimeState(bindings = listOf(global(HotkeyAction.OPEN_OCR, KeyEvent.VK_A + index, InputEvent.CTRL_DOWN_MASK)))
                )
            }, "reconcile-$index")
        }
        threads.forEach { it.start() }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        go.countDown()
        threads.forEach { it.join(10_000) }
        threads.forEach { assertFalse(it.isAlive) }

        // Every apply the fake backend ever recorded minted a token via the same ledger; none of
        // those ids may collide, however the reconciliations interleaved.
        val allIds = backend.applied.flatMap { set -> set.map { it.id } }
        assertEquals(allIds.size, allIds.toSet().size, "a token id was reused across reconciliations: $allIds")
    }

    @Test
    fun `degraded registration is not re-accepted by a reconciliation that started earlier but finishes later`() {
        var ocrCalls = 0
        val backend = FakeGlobalInputBackend()
        val listener = newListener(backend) { ocrCalls++ }
        listener.updateRuntimeState(
            InputRuntimeState(bindings = listOf(global(HotkeyAction.OPEN_OCR, KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK)))
        )
        listener.initialize()
        val originalToken = listener.acceptedTokenFor(HotkeyAction.OPEN_OCR)
        assertNotNull(originalToken)

        // The platform will refuse to release ctrl+I once asked to.
        backend.refusedUnregister += "control+KeyI"

        val (entered, release) = backend.pauseFirstApply()

        // Reconcile A: disables OPEN_OCR entirely. Its native release is refused, so this is a
        // degraded apply: the accelerator stays installed, but A must still retire its token.
        val threadA = Thread({
            listener.updateRuntimeState(InputRuntimeState(bindings = emptyList()))
        }, "reconcile-A-disable")
        threadA.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        // Reconcile B: re-enables OPEN_OCR on the SAME accelerator, started while A is still
        // paused mid-apply and queued behind it.
        val threadB = Thread({
            listener.updateRuntimeState(
                InputRuntimeState(bindings = listOf(global(HotkeyAction.OPEN_OCR, KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK)))
            )
        }, "reconcile-B-reenable")
        threadB.start()

        awaitQueuedReconcile(listener)
        release.countDown()
        threadA.join(10_000)
        threadB.join(10_000)
        assertFalse(threadA.isAlive)
        assertFalse(threadB.isAlive)

        // B, which can only have planned and accepted after A fully published (retiring the
        // original token and recording the degraded leftover), must mint and accept a FRESH
        // token, never resurrect the original one A already retired.
        val finalToken = listener.acceptedTokenFor(HotkeyAction.OPEN_OCR)
        assertNotNull(finalToken)
        assertNotEquals(originalToken, finalToken, "B must not resurrect A's retired token")

        // The original token must never dispatch again, even though ctrl+I is still physically
        // installed the whole time (the platform never released it).
        backend.emit(GlobalInputEvent.Hotkey(originalToken))
        assertEquals(0, ocrCalls, "the original, retired token must not dispatch")
    }

    @Test
    fun `reconcile paused mid-transaction blocks shutdown until it publishes, then shutdown wins`() {
        val backend = FakeGlobalInputBackend()
        val listener = newListener(backend)
        listener.updateRuntimeState(
            InputRuntimeState(bindings = listOf(global(HotkeyAction.OPEN_OCR, KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK)))
        )
        listener.initialize()
        assertNotNull(listener.acceptedTokenFor(HotkeyAction.OPEN_OCR))

        val (entered, release) = backend.pauseFirstApply()

        val threadReconcile = Thread({
            listener.updateRuntimeState(
                InputRuntimeState(bindings = listOf(global(HotkeyAction.OPEN_OCR, KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK)))
            )
        }, "reconcile-racing-shutdown")
        threadReconcile.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        val threadShutdown = Thread({ listener.shutdown() }, "shutdown")
        threadShutdown.start()
        awaitQueuedReconcile(listener)

        release.countDown()
        threadReconcile.join(10_000)
        threadShutdown.join(10_000)
        assertFalse(threadReconcile.isAlive)
        assertFalse(threadShutdown.isAlive)

        // The reconcile that was already mid-transaction is guaranteed to publish first (shutdown
        // could only ever queue behind it), and shutdown, running strictly after, must have the
        // final word: nothing the reconcile published may survive it.
        assertNull(
            listener.acceptedTokenFor(HotkeyAction.OPEN_OCR),
            "shutdown must be the last word: no token may remain accepted afterwards"
        )
        assertEquals(1, backend.closeCount, "the backend must be closed exactly once")
    }

    @Test
    fun `shutdown paused mid-transaction blocks a racing reconcile, which then finds nothing to do`() {
        val backend = FakeGlobalInputBackend()
        val listener = newListener(backend)
        listener.updateRuntimeState(
            InputRuntimeState(bindings = listOf(global(HotkeyAction.OPEN_OCR, KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK)))
        )
        listener.initialize()
        assertNotNull(listener.acceptedTokenFor(HotkeyAction.OPEN_OCR))

        val (entered, release) = backend.pauseClose()

        val threadShutdown = Thread({ listener.shutdown() }, "shutdown-first")
        threadShutdown.start()
        assertTrue(entered.await(10, TimeUnit.SECONDS), "shutdown must reach close() before the race starts")

        // A reconcile racing in while shutdown is mid-teardown (backend not yet nulled,
        // `initialized` not yet cleared) must not observe a half-torn-down backend: it can only
        // run once shutdown's own critical section (including `initialized.set(false)`) has
        // fully completed, at which point it must see "not initialized" and do nothing.
        val threadReconcile = Thread({
            listener.updateRuntimeState(
                InputRuntimeState(bindings = listOf(global(HotkeyAction.OPEN_OCR, KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK)))
            )
        }, "reconcile-racing-shutdown-2")
        threadReconcile.start()
        awaitQueuedReconcile(listener)

        release.countDown()
        threadShutdown.join(10_000)
        threadReconcile.join(10_000)
        assertFalse(threadShutdown.isAlive)
        assertFalse(threadReconcile.isAlive)

        // Shutdown fully completed (including `initialized = false`) before the queued reconcile
        // could even start its own transaction, so the reconcile must have been a no-op: nothing
        // it might have wanted to apply can have reached the (closed) backend.
        assertNull(listener.acceptedTokenFor(HotkeyAction.OPEN_OCR))
        assertEquals(1, backend.closeCount)
        assertEquals(1, backend.applied.size, "the queued reconcile must not have applied anything post-shutdown")
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle serialization: initialize() and shutdown() must be one serialized transaction.
    //
    // The pre-fix shape checked/transitioned `initialized` outside the reconcile lock, so a
    // shutdown that won the lock first could tear down an uninitialized listener and clear the
    // flag while an initialize that lost the race still went on to create a live backend, or a
    // legitimate initialize queued behind a shutdown could be silently dropped. These tests use
    // distinct backends per generation (which is what makes an orphaned or duplicated generation
    // observable) plus the close/queued-lock seams, never a sleep.
    // ---------------------------------------------------------------------------------------

    /**
     * A listener whose backend factory mints a fresh [FakeGlobalInputBackend] per initialize and
     * records each in [created]. Reusing one backend instance would hide an orphaned or duplicated
     * generation, because every generation would look identical.
     */
    private fun lifecycleListener(
        created: MutableList<FakeGlobalInputBackend>,
        factory: () -> FakeGlobalInputBackend = {
            FakeGlobalInputBackend().also { created += it }
        },
    ): MainGlobalKeyListener {
        val listener = MainGlobalKeyListener(
            scope = scope,
            logger = RecordingLogger(),
            onShowApp = {}, onShowQuickTranslate = {}, onListenToText = {},
            onOpenSnippingTool = {}, onReplaceWithTranslation = {}, onCycleTargetLanguage = {},
            backendFactory = factory,
            selectionCaptureFactory = { _, _, simulateCopy, logger ->
                SelectionCapture(RecordingClipboard("original"), FakeChangeMonitor(), simulateCopy, logger)
            }
        )
        listener.copyInjector = CopyInjector { true }
        return listener
    }

    @Test
    fun `initialize requested while shutdown holds the lifecycle lock is serialized and honored`() {
        val created = CopyOnWriteArrayList<FakeGlobalInputBackend>()
        val listener = lifecycleListener(created)

        listener.initialize()
        val first = created.single()
        assertTrue(listener.isInitialized())

        // Shutdown takes the lifecycle lock and pauses inside close(), still holding it.
        val (entered, release) = first.pauseClose()
        val shutdownThread = Thread({ listener.shutdown() }, "shutdown-owner")
        shutdownThread.start()
        assertTrue(
            entered.await(10, TimeUnit.SECONDS),
            "shutdown must reach close() while holding the lifecycle lock"
        )

        // A concurrent initialize must queue on the lifecycle lock, not be silently dropped by an
        // outside-lock initialized check (which would observe true and return immediately).
        val initializeThread = Thread({ listener.initialize() }, "initialize-serialized-after-shutdown")
        initializeThread.start()
        awaitQueuedReconcile(listener)

        release.countDown()
        shutdownThread.join(10_000)
        initializeThread.join(10_000)
        assertFalse(shutdownThread.isAlive)
        assertFalse(initializeThread.isAlive)

        // The queued initialize ran for real: a second, distinct backend exists, the teardown
        // closed the first exactly once, and the post-race generation is coherent.
        assertEquals(2, created.size, "the queued initialize must be honored, not lost")
        val second = created[1]
        assertSame(second, listener.inputBackend())
        assertTrue(listener.isInitialized(), "a live backend must never be left reporting uninitialized")
        assertEquals(1, first.closeCount)
        assertEquals(0, second.closeCount)

        // Shutdown after the race closes the actual active backend exactly once.
        listener.shutdown()
        assertNull(listener.inputBackend())
        assertFalse(listener.isInitialized())
        assertEquals(1, second.closeCount, "the post-race backend must be closed exactly once")
        assertEquals(1, first.closeCount, "the earlier backend must not be closed again")
    }

    @Test
    fun `duplicate concurrent initialize creates only one active backend`() {
        val created = CopyOnWriteArrayList<FakeGlobalInputBackend>()
        val listener = lifecycleListener(created)

        val threads = 8
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val workers = (0 until threads).map { index ->
            Thread({
                ready.countDown()
                assertTrue(go.await(10, TimeUnit.SECONDS))
                listener.initialize()
            }, "initialize-$index")
        }
        workers.forEach { it.start() }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        go.countDown()
        workers.forEach { it.join(10_000) }
        workers.forEach { assertFalse(it.isAlive) }

        assertEquals(1, created.size, "exactly one initialize may create a backend")
        assertSame(created.single(), listener.inputBackend())
        assertTrue(listener.isInitialized())
        assertEquals(0, created.single().closeCount)
    }

    /**
     * Safety-invariant stress: whichever way a concurrently started initialize and shutdown are
     * ordered, the listener must never end a round with a live backend while reporting itself
     * uninitialized. On the pre-fix shape, an initialize that lost the lock race could return
     * exactly that way, leaving its backend (and native runtime) orphaned when the next shutdown
     * saw `initialized == false` and returned.
     */
    @Test
    fun `initialize racing shutdown never leaves a live backend with initialized false`() {
        repeat(64) { round ->
            val created = CopyOnWriteArrayList<FakeGlobalInputBackend>()
            val listener = lifecycleListener(created)
            val barrier = CyclicBarrier(2)
            val initializer = Thread({
                barrier.await(10, TimeUnit.SECONDS)
                listener.initialize()
            }, "initialize-$round")
            val shutter = Thread({
                barrier.await(10, TimeUnit.SECONDS)
                listener.shutdown()
            }, "shutdown-$round")
            initializer.start()
            shutter.start()
            initializer.join(10_000)
            shutter.join(10_000)
            assertFalse(initializer.isAlive)
            assertFalse(shutter.isAlive)

            assertTrue(
                listener.inputBackend() == null || listener.isInitialized(),
                "round $round: backend and initialized must describe one lifecycle generation"
            )

            // Close whatever the race left live so no generation's backend is orphaned; a coherent
            // lifecycle always permits this to fully retire the survivor.
            listener.shutdown()
            assertNull(listener.inputBackend())
            assertFalse(listener.isInitialized())
        }
    }

    @Test
    fun `initialization failure on a racing thread still allows a later retry`() {
        var calls = 0
        val backend = FakeGlobalInputBackend()
        val listener = lifecycleListener(CopyOnWriteArrayList()) {
            calls++
            if (calls == 1) throw RuntimeException("first attempt fails")
            backend
        }

        val first = Thread({ listener.initialize() }, "initialize-fails")
        first.start()
        first.join(10_000)
        assertFalse(first.isAlive)
        assertNull(listener.inputBackend())
        assertFalse(listener.isInitialized(), "a failed initialize must leave nothing behind")

        val retry = Thread({ listener.initialize() }, "initialize-retry")
        retry.start()
        retry.join(10_000)
        assertFalse(retry.isAlive)
        assertSame(backend, listener.inputBackend())
        assertTrue(listener.isInitialized())
    }
}
