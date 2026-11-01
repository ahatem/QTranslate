package com.github.ahatem.qtranslate.core

import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Holds one start in dispatch while another thread replaces its already-installed job. */
internal class ConcurrentSessionStarts(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    private val holdNext = AtomicBoolean()
    private val dispatchEntered = CountDownLatch(1)
    private val releaseDispatch = CountDownLatch(1)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context, block)
        if (holdNext.compareAndSet(true, false)) {
            dispatchEntered.countDown()
            check(releaseDispatch.await(10, TimeUnit.SECONDS)) { "first start was never released" }
        }
    }

    fun overlap(first: () -> Unit, replacement: () -> Unit, whileFirstIsStarting: () -> Unit = {}) {
        val threads = Executors.newFixedThreadPool(2)
        holdNext.set(true)
        val firstCall = threads.submit(first)
        try {
            assertTrue(dispatchEntered.await(10, TimeUnit.SECONDS), "first start did not enter dispatch")
            whileFirstIsStarting()
            threads.submit(replacement).get(10, TimeUnit.SECONDS)
            assertFalse(firstCall.isDone, "start calls must overlap on different threads")
        } finally {
            releaseDispatch.countDown()
            try { firstCall.get(10, TimeUnit.SECONDS) } finally { threads.shutdownNow() }
        }
    }
}
