package com.github.ahatem.qtranslate.core.main.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owns dictionary replacement; callbacks gate state and buffered events with their unique session identity. */
internal class DictionaryLookupSession(
    private val scope: CoroutineScope,
    private val lookup: suspend (DictionaryLookupRequest, suspend () -> Unit) -> DictionaryLookupOutcome,
    private val onStarted: suspend (DictionaryLookupRequest, (() -> Unit) -> Unit) -> Unit,
    private val onResult: suspend (DictionaryLookupOutcome, (() -> Unit) -> Unit) -> Unit
) {
    private val lock = Any()
    private var job: Job? = null
    private var generation = Any()
    fun start(request: DictionaryLookupRequest) {
        val next = synchronized(lock) {
            job?.cancel()
            val current = Any().also { generation = it }
            val publish: (() -> Unit) -> Unit = { action -> synchronized(lock) {
                if (current === generation) action()
            } }
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val outcome = lookup(request) {
                        if (synchronized(lock) { current === generation }) onStarted(request, publish)
                    }
                    if (synchronized(lock) { current === generation }) onResult(outcome, publish)
                } finally { synchronized(lock) { if (current === generation) job = null } }
            }.also { job = it }
        }
        next.start()
    }
    fun cancel() = synchronized(lock) { generation = Any(); job?.cancel(); job = null }
}

/** Separate image-search lifetime and policy; no shared asynchronous workflow abstraction. */
internal class ImageLookupSession(
    private val scope: CoroutineScope,
    private val search: suspend (ImageLookupRequest, suspend () -> Unit) -> ImageLookupOutcome,
    private val onStarted: suspend (ImageLookupRequest, (() -> Unit) -> Unit) -> Unit,
    private val onResult: suspend (ImageLookupOutcome, (() -> Unit) -> Unit) -> Unit
) {
    private val lock = Any()
    private var job: Job? = null
    private var generation = Any()
    fun start(request: ImageLookupRequest) {
        val next = synchronized(lock) {
            job?.cancel()
            val current = Any().also { generation = it }
            val publish: (() -> Unit) -> Unit = { action -> synchronized(lock) {
                if (current === generation) action()
            } }
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val outcome = search(request) {
                        if (synchronized(lock) { current === generation }) onStarted(request, publish)
                    }
                    if (synchronized(lock) { current === generation }) onResult(outcome, publish)
                } finally { synchronized(lock) { if (current === generation) job = null } }
            }.also { job = it }
        }
        next.start()
    }
    fun cancel() = synchronized(lock) { generation = Any(); job?.cancel(); job = null }
}
