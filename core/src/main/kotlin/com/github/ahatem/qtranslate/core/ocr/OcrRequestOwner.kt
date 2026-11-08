package com.github.ahatem.qtranslate.core.ocr

import kotlinx.coroutines.Job

/**
 * Owns one OCR request: its identity, its job slot and its publication gate.
 *
 * A generation counter checked outside the state write is not enough. Ownership can change
 * between "still mine?" and the state commit, and a cancelled coroutine may already be inside
 * that commit. Here the identity transition and the publication block share one lock, so a
 * superseded request can never begin a publication after a newer request has taken ownership.
 *
 * The lock only ever spans a short in-memory mutation. Callers detach the superseded job and
 * cancel it outside the lock; no provider call, event send or coroutine start happens here.
 */
internal class OcrRequestOwner {

    internal data class Claim(val token: Long, val previousJob: Job?)

    internal data class Closed(val previousJob: Job?)

    private val lock = Any()
    private var generation: Long = 0L
    private var job: Job? = null
    private var closed: Boolean = false

    /**
     * Takes ownership and runs [whileOwned] while that ownership is still exclusive. The lambda
     * is the caller's short state transition, not arbitrary work.
     */
    fun claim(whileOwned: (token: Long) -> Unit = {}): Claim? = synchronized(lock) {
        if (closed) return null
        val token = ++generation
        val previous = job
        job = null
        whileOwned(token)
        Claim(token, previous)
    }

    /** Installs [candidate] only while [token] still owns the request. */
    fun install(token: Long, candidate: Job): Boolean = synchronized(lock) {
        if (closed || token != generation) return false
        job = candidate
        true
    }

    fun isCurrent(token: Long): Boolean = synchronized(lock) {
        !closed && token == generation
    }

    /**
     * Runs a short publication only while [token] owns it, with ownership unable to change until
     * the block returns. Used for every state write, event publication decision and loading flag
     * that belongs to an OCR request.
     */
    fun <T> withCurrent(token: Long, block: () -> T): T? = synchronized(lock) {
        if (closed || token != generation) null else block()
    }

    /** The current token, for a public operation that acts on the existing request. */
    fun currentToken(): Long? = synchronized(lock) {
        if (closed) null else generation
    }

    fun close(whileClosed: (() -> Unit)? = null): Closed? = synchronized(lock) {
        if (closed) return null
        closed = true
        generation += 1
        val previous = job
        job = null
        whileClosed?.invoke()
        Closed(previous)
    }
}
