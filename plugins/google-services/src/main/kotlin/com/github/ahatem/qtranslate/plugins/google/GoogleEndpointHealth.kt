package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * A caller holds a [Permit] for the whole request. Every phase change bumps [generation], and a
 * permit only counts while it still matches the current generation, so a request that began before a
 * transition can never rewrite the newer state.
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

    /**
     * Permission to call the primary endpoint. It stays valid only while the circuit's generation is
     * unchanged; a permit from an older generation is ignored by every recorder.
     *
     * [isProbe] marks the single request allowed while the circuit is half open. A probe heals or
     * reopens the circuit when it resolves, so a request that produces no outcome must hand its
     * permit back with [releasePrimary].
     */
    class Permit internal constructor(
        internal val generation: Long,
        val isProbe: Boolean
    )

    private val mutex = Mutex()
    private var state = State.HEALTHY
    private var generation = 0L
    private var consecutiveFailures = 0
    private var openCount = 0
    private var openUntilMillis = 0L
    private var probeInFlight = false

    /**
     * Returns a permit when the caller may request the primary endpoint now, or null when it must use
     * its fallback instead.
     *
     * While healthy every caller is admitted. While the circuit is open callers are sent to their
     * fallback immediately, without paying the primary request timeout. After the cooldown exactly
     * one caller is promoted to a half-open probe; every other caller keeps using its fallback until
     * that probe resolves.
     */
    suspend fun tryAcquirePrimary(): Permit? = mutex.withLock {
        when {
            state == State.HEALTHY -> Permit(generation, isProbe = false)
            state == State.OPEN_COOLDOWN && clock() >= openUntilMillis && !probeInFlight -> beginProbe()
            else -> null
        }
    }

    /**
     * Records a successful primary response. A probe heals the circuit; an ordinary request only
     * clears the consecutive-failure count.
     */
    suspend fun recordSuccess(permit: Permit) = mutex.withLock {
        if (permit.generation != generation) return@withLock
        if (permit.isProbe) {
            state = State.HEALTHY
            consecutiveFailures = 0
            openCount = 0
            openUntilMillis = 0L
            probeInFlight = false
            generation++
        } else {
            consecutiveFailures = 0
        }
    }

    /**
     * Records a transient primary failure. A probe reopens the cooldown. An ordinary request opens
     * the cooldown on the second consecutive failure, or immediately on a rate limit; a Retry-After
     * hint lengthens the cooldown when present.
     */
    suspend fun recordTransientFailure(permit: Permit, error: ServiceError) = mutex.withLock {
        if (permit.generation != generation) return@withLock
        val retryAfterSeconds = (error as? ServiceError.RateLimitError)?.retryAfterSeconds
        if (permit.isProbe) {
            openCooldown(retryAfterSeconds)
            return@withLock
        }
        consecutiveFailures++
        if (error is ServiceError.RateLimitError || consecutiveFailures >= FAILURE_THRESHOLD) {
            openCooldown(retryAfterSeconds)
        }
    }

    /**
     * Returns a permit that produced no usable outcome, for example a cancelled request. It records
     * neither success nor failure. An ordinary permit changes nothing; a probe permit reopens the
     * cooldown so the circuit can never be left waiting on a probe that will not return.
     *
     * This is called from `finally` blocks, so it runs on the cancellation path too. The cleanup
     * itself is therefore non-cancellable: a cancelled coroutine must still be able to take the
     * lock when another caller holds it, or a probe released during cancellation would leave
     * [probeInFlight] set and strand the circuit half open for good. Only the release is protected;
     * the request that produced the permit stays cancellable.
     */
    suspend fun releasePrimary(permit: Permit) = withContext(NonCancellable) {
        mutex.withLock {
            if (permit.generation != generation) return@withLock
            if (!permit.isProbe) return@withLock
            probeInFlight = false
            state = State.OPEN_COOLDOWN
            openUntilMillis = clock() + cooldownMillis(null)
            generation++
        }
    }

    private fun beginProbe(): Permit {
        state = State.HALF_OPEN
        probeInFlight = true
        return Permit(generation, isProbe = true)
    }

    private fun openCooldown(retryAfterSeconds: Int?) {
        openCount++
        state = State.OPEN_COOLDOWN
        openUntilMillis = clock() + cooldownMillis(retryAfterSeconds)
        probeInFlight = false
        generation++
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
        private const val JITTER_MILLIS = 1_000L
    }
}
