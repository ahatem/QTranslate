package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Tracks the health of the primary Google endpoint shared by the translator and the spell checker.
 *
 * The unofficial primary host throttles intermittently. Waiting for its request timeout on every
 * call is what turns a throttled endpoint into a repeated multi-second stall, so callers consult
 * [tryAcquirePrimary] first: while the circuit is open they skip the primary entirely and use their
 * fallback. Once the cooldown elapses a single probe is permitted, and its outcome decides whether
 * the circuit closes or reopens.
 *
 * Only the primary endpoint is tracked. The fallback and the official API are separate hosts and do
 * not feed this state.
 *
 * [clock] and [jitterMillis] are injectable so cooldown and half-open transitions are deterministic
 * in tests.
 */
class GoogleEndpointHealth(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val jitterMillis: () -> Long = { Random.nextLong(JITTER_MILLIS + 1) }
) {

    enum class State { HEALTHY, OPEN_COOLDOWN, HALF_OPEN }

    private val mutex = Mutex()
    private var state = State.HEALTHY
    private var consecutiveFailures = 0
    private var openCount = 0
    private var openUntilMillis = 0L
    private var probeDeadlineMillis = 0L

    /**
     * Returns true when the caller may request the primary endpoint now.
     *
     * While healthy every caller may proceed. While the circuit is open the caller is sent to its
     * fallback immediately, without paying the primary request timeout. After the cooldown, exactly
     * one caller is promoted to a half-open probe; the rest keep using their fallback until the
     * probe resolves.
     */
    suspend fun tryAcquirePrimary(): Boolean = mutex.withLock {
        when (state) {
            State.HEALTHY -> true
            State.OPEN_COOLDOWN -> if (clock() >= openUntilMillis) beginProbe() else false
            State.HALF_OPEN -> if (clock() >= probeDeadlineMillis) beginProbe() else false
        }
    }

    /** Records a successful primary response and closes the circuit. */
    suspend fun recordSuccess() = mutex.withLock {
        state = State.HEALTHY
        consecutiveFailures = 0
        openCount = 0
        openUntilMillis = 0L
        probeDeadlineMillis = 0L
    }

    /**
     * Records a transient primary failure. Opens the circuit on the second consecutive failure, on
     * an explicit Retry-After, or when a half-open probe fails.
     */
    suspend fun recordTransientFailure(error: ServiceError) = mutex.withLock {
        consecutiveFailures++
        val retryAfterSeconds = (error as? ServiceError.RateLimitError)?.retryAfterSeconds
        val shouldOpen = state == State.HALF_OPEN ||
            retryAfterSeconds != null ||
            consecutiveFailures >= FAILURE_THRESHOLD
        if (shouldOpen) {
            openCount++
            state = State.OPEN_COOLDOWN
            openUntilMillis = clock() + cooldownMillis(retryAfterSeconds)
        }
    }

    private fun beginProbe(): Boolean {
        state = State.HALF_OPEN
        probeDeadlineMillis = clock() + PROBE_TIMEOUT_MILLIS
        return true
    }

    private fun cooldownMillis(retryAfterSeconds: Int?): Long {
        var backoff = BASE_COOLDOWN_MILLIS
        repeat((openCount - 1).coerceIn(0, MAX_DOUBLINGS)) {
            backoff = (backoff * 2).coerceAtMost(MAX_COOLDOWN_MILLIS)
        }
        val retryAfterMillis = retryAfterSeconds?.coerceAtLeast(0)?.let { it * 1_000L }
        return if (retryAfterMillis != null) {
            maxOf(retryAfterMillis, backoff)
        } else {
            backoff + jitterMillis()
        }
    }

    companion object {
        private const val FAILURE_THRESHOLD = 2
        private const val BASE_COOLDOWN_MILLIS = 2_000L
        private const val MAX_COOLDOWN_MILLIS = 60_000L
        private const val MAX_DOUBLINGS = 5
        private const val PROBE_TIMEOUT_MILLIS = 10_000L
        private const val JITTER_MILLIS = 1_000L
    }
}
