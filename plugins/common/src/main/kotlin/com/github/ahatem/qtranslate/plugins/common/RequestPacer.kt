package com.github.ahatem.qtranslate.plugins.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends one request at a time to a provider and keeps them at least [minimumIntervalMillis] apart.
 *
 * The interval counts from the last [markSent], which a caller makes once a request has actually
 * reached the provider, so a request that failed before sending does not delay the next one.
 * A zero or negative interval only serialises.
 */
class RequestPacer(private val minimumIntervalMillis: Long) {
    private val mutex = Mutex()
    private var lastSentAtNanos = 0L

    /** Runs [block] alone, after waiting out whatever remains of the interval. */
    suspend fun <T> paced(block: suspend () -> T): T = mutex.withLock {
        awaitInterval()
        block()
    }

    /** Records that a request reached the provider just now. Call it from inside [paced]. */
    fun markSent() {
        lastSentAtNanos = System.nanoTime()
    }

    private suspend fun awaitInterval() {
        if (lastSentAtNanos == 0L || minimumIntervalMillis <= 0) return
        val elapsedMillis = (System.nanoTime() - lastSentAtNanos) / 1_000_000
        val remainingMillis = minimumIntervalMillis - elapsedMillis
        if (remainingMillis > 0) delay(remainingMillis)
    }
}
