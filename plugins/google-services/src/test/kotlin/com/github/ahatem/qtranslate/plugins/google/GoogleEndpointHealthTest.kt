package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertNotNull

class GoogleEndpointHealthTest {

    /**
     * The permit release runs from a `finally`, so it is on the cancellation path too. If the
     * release could itself be cancelled while it waited for the mutex, a contended circuit would
     * keep a probe lease that never clears and stay half open. This holds the mutex from the test
     * and cancels the release while it waits, so the cleanup has to survive.
     *
     * The clock seam cannot be used to hold the mutex here: `tryAcquirePrimary` only reads the
     * clock on the open-cooldown branch, and a permit is only valid in the half-open state, so no
     * operation that reads the clock can run while a live probe permit exists. Holding the mutex
     * through the state's own lock is therefore the only deterministic construction.
     */
    @Test
    fun `a probe release cancelled while it waits for the mutex still cleans up`() = runBlocking {
        val clock = AtomicLong(0)
        val health = GoogleEndpointHealth({ clock.get() }, { 0L })

        // Two transient failures open the circuit; once the cooldown elapses a probe is admitted.
        health.recordTransientFailure(health.tryAcquirePrimary()!!, ServiceError.TimeoutError("down"))
        health.recordTransientFailure(health.tryAcquirePrimary()!!, ServiceError.TimeoutError("down"))
        clock.set(2_000)
        val probe = health.tryAcquirePrimary()!!

        // Hold the health mutex so the release below has to wait for it, the way it would when
        // another caller is mid transition.
        val mutex = mutexOf(health)
        val holding = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val holder = launch {
            mutex.lock()
            holding.complete(Unit)
            releaseHolder.await()
            mutex.unlock()
        }
        holding.await()

        // The release reaches the held mutex and waits there. Cancelling the coroutine at this
        // point is what the cleanup has to survive; the mutex is not released until after the
        // cancel, so a cancellable wait would drop the cleanup.
        val reachedRelease = CompletableDeferred<Unit>()
        val releaser = launch {
            reachedRelease.complete(Unit)
            health.releasePrimary(probe)
        }
        reachedRelease.await()
        releaser.cancel()

        releaseHolder.complete(Unit)
        holder.join()
        releaser.join()

        // The release reopened the cooldown, so a probe is admissible again once it elapses. Had
        // the cleanup been skipped, the circuit would still be half open and this would be null.
        clock.set(4_000)
        assertNotNull(health.tryAcquirePrimary())
        Unit
    }

    private fun mutexOf(health: GoogleEndpointHealth): Mutex {
        val field = GoogleEndpointHealth::class.java.getDeclaredField("mutex")
        field.isAccessible = true
        return field.get(health) as Mutex
    }
}
