package com.github.ahatem.qtranslate.core.workspace.translation

import kotlinx.coroutines.Job

/**
 * Owns the primary translation: request identity, comparison generation, and the job slot, all
 * under one lock.
 *
 * These three used to move independently. A caller could claim request A, be delayed, and then
 * cancel the global job slot — by which time request B had claimed it, so A cancelled B while B
 * was still the current request. Keeping the identity and the slot in one transition means a
 * stale caller can never cancel or replace an operation it no longer owns.
 *
 * No callback, subsystem call, or coroutine start happens while the lock is held; callers detach
 * the superseded job and cancel it afterwards.
 */
internal class TranslationRequestOwner {

    internal data class Claim(val requestId: Long, val generation: Long, val previousJob: Job?)

    internal data class Canceled(val generation: Long, val previousJob: Job?, val requestId: Long, val secondaryGeneration: Long)

    private val lock = Any()
    private var nextRequestId: Long = 0L
    private var nextGeneration: Long = 0L
    private var currentRequestId: Long = 0L
    private var currentGeneration: Long = 0L
    private var job: Job? = null
    private var secondaryGeneration: Long = 0L

    /** Claims a new request and generation, detaching the job it supersedes. */
    fun claim(secondaryGeneration: Long = 0L): Claim = synchronized(lock) {
        this.secondaryGeneration = secondaryGeneration
        val id = ++nextRequestId
        val generation = ++nextGeneration
        currentRequestId = id
        currentGeneration = generation
        val previous = job
        job = null
        Claim(id, generation, previous)
    }

    /**
     * Installs [candidate] only while [claim] still owns the request. A stale caller is refused,
     * so it cannot replace (and later cancel) a newer request's job.
     */
    fun install(claim: Claim, candidate: Job): Boolean {
        synchronized(lock) {
            if (currentRequestId != claim.requestId) return false
            job = candidate
            return true
        }
    }

    /** Invalidates the current request and generation, detaching its job for cancellation. */
    fun cancel(): Canceled = synchronized(lock) {
        currentRequestId = ++nextRequestId
        currentGeneration = ++nextGeneration
        val previous = job
        job = null
        Canceled(currentGeneration, previous, currentRequestId, secondaryGeneration)
    }

    /** Invalidates only while [requestId] is still current; a newer request is left untouched. */
    fun cancelIfCurrent(requestId: Long): Canceled? = synchronized(lock) {
        if (currentRequestId != requestId) return null
        currentRequestId = ++nextRequestId
        currentGeneration = ++nextGeneration
        val previous = job
        job = null
        Canceled(currentGeneration, previous, currentRequestId, secondaryGeneration)
    }

    /** Advances only the comparison generation; the current request id stays owned. */
    fun invalidateGeneration(): Long = synchronized(lock) {
        currentGeneration = ++nextGeneration
        currentGeneration
    }

    fun isCurrent(requestId: Long): Boolean = synchronized(lock) { currentRequestId == requestId }

    /** The identity of the request currently allowed to publish, for origin-specific ownership. */
    fun currentRequestId(): Long = synchronized(lock) { currentRequestId }

    /** Runs [block] only while [requestId] owns publication. Used for a short state update. */
    fun <T> withCurrentRequest(requestId: Long, block: () -> T): T? = synchronized(lock) {
        if (currentRequestId == requestId) block() else null
    }

    /** Runs [block] only while [generation] owns comparison publication. */
    fun <T> withCurrentGeneration(generation: Long, block: () -> T): T? = synchronized(lock) {
        if (currentGeneration == generation) block() else null
    }
}
