package com.github.ahatem.qtranslate.core.writing

import kotlinx.coroutines.Job

/**
 * Owns one writing/selection work item: its identity, job slot and publication gate.
 *
 * Mirrors [com.github.ahatem.qtranslate.core.ocr.OcrRequestOwner]: a generation counter checked
 * outside the state write is not enough. Ownership and the publication block share one lock, so a
 * superseded request can never begin a publication after a newer request has taken ownership.
 */
class SelectionTextWorkOwner {

    data class Claim(val token: Long, val previousJob: Job?)

    data class Closed(val previousJob: Job?)

    private val lock = Any()
    private var generation: Long = 0L
    private var job: Job? = null
    private var closed: Boolean = false

    fun claim(whileOwned: (token: Long) -> Unit = {}): Claim? = synchronized(lock) {
        if (closed) return null
        val token = ++generation
        val previous = job
        job = null
        whileOwned(token)
        Claim(token, previous)
    }

    fun install(token: Long, candidate: Job): Boolean = synchronized(lock) {
        if (closed || token != generation) return false
        job = candidate
        true
    }

    fun isCurrent(token: Long): Boolean = synchronized(lock) {
        !closed && token == generation
    }

    fun <T> withCurrent(token: Long, block: () -> T): T? = synchronized(lock) {
        if (closed || token != generation) null else block()
    }

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
