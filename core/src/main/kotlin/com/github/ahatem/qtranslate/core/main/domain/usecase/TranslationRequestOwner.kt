package com.github.ahatem.qtranslate.core.main.domain.usecase

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

    internal data class Canceled(val generation: Long, val previousJob: Job?)

    private val lock = Any()
    private var nextRequestId: Long = 0L
    private var nextGeneration: Long = 0L
    private var currentRequestId: Long = 0L
    private var currentGeneration: Long = 0L
    private var job: Job? = null

    /** Claims a new request and generation, detaching the job it supersedes. */
    fun claim(): Claim = synchronized(lock) {
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
        Canceled(currentGeneration, previous)
    }

    /** Advances only the comparison generation; the current request id stays owned. */
    fun invalidateGeneration(): Long = synchronized(lock) {
        currentGeneration = ++nextGeneration
        currentGeneration
    }

    fun isCurrent(requestId: Long): Boolean = synchronized(lock) { currentRequestId == requestId }

    /** Runs [block] only while [requestId] owns publication. Used for a short state update. */
    fun <T> withCurrentRequest(requestId: Long, block: () -> T): T? = synchronized(lock) {
        if (currentRequestId == requestId) block() else null
    }

    /** Runs [block] only while [generation] owns comparison publication. */
    fun <T> withCurrentGeneration(generation: Long, block: () -> T): T? = synchronized(lock) {
        if (currentGeneration == generation) block() else null
    }
}
