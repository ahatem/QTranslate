package com.github.ahatem.qtranslate.core.workspace.translation

import kotlinx.coroutines.Job

/**
 * Owns the current secondary-output operation: one generation token and one job slot, moved
 * together under one lock.
 *
 * Publication gating alone is not enough. If a newer request takes over the generation while an
 * older caller is still between claiming and registering its job, the older caller must not be
 * able to overwrite the slot — that would make [cancel] cancel the wrong operation and leave the
 * real one running. Keeping the token and the slot in one transition makes "current generation"
 * and "current job" always describe the same operation.
 *
 * The lock never spans a call into another subsystem: callers detach the previous job under the
 * lock and cancel it afterwards.
 */
internal class ExtraOutputWorkOwner {

    internal class Claim(val generation: Long, val previousJob: Job?)

    private val lock = Any()
    private var generation: Long = 0L
    private var job: Job? = null

    /**
     * Starts a new ownership generation and detaches the job it supersedes. The caller cancels
     * [Claim.previousJob] outside the lock.
     */
    fun claim(): Claim = synchronized(lock) {
        generation += 1
        val previous = job
        job = null
        Claim(generation, previous)
    }

    /**
     * Installs [candidate] as the current operation, but only while [claim] still owns the
     * generation. A stale claim is refused, so it can never displace the live job.
     */
    fun register(claim: Claim, candidate: Job): Boolean = register(claim.generation, candidate)

    /** As [register], for a caller that carries only the token it was granted. */
    fun register(token: Long, candidate: Job): Boolean {
        synchronized(lock) {
            if (token != generation) return false
            job = candidate
            return true
        }
    }

    /** Detaches and returns the current job, e.g. for cancellation. */
    fun detach(): Job? = synchronized(lock) {
        val current = job
        job = null
        current
    }

    /** Whether [token] still owns the publication of a result. */
    fun isCurrent(token: Long): Boolean = synchronized(lock) { token == generation }

    /**
     * Runs [block] only while [token] still owns the generation, so a check-then-publish cannot
     * be interleaved by a new claim. The block is a short state update, never a provider call.
     */
    fun <T> withCurrent(token: Long, block: () -> T): T? = synchronized(lock) {
        if (token == generation) block() else null
    }

    /** The current generation, for callers that perform work without a separate job slot. */
    fun currentGeneration(): Long = synchronized(lock) { generation }
}
