package com.github.ahatem.qtranslate.core.document

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns document job replacement and publication under one lock. Each start has a unique identity.
 * Suspending callbacks must use their publication gate for effects after suspension (or buffering).
 */
internal class DocumentTranslationSession(
    private val scope: CoroutineScope,
    private val translate: suspend (DocumentTranslationRequest, (DocumentTranslationProgress) -> Unit) -> File,
    private val onProgress: (DocumentTranslationProgress?) -> Unit,
    private val onCompleted: suspend (File, (() -> Unit) -> Unit) -> Unit,
    private val onFailed: suspend (String, (() -> Unit) -> Unit) -> Unit
) {
    private val lock = Any()
    private var job: Job? = null
    private var generation = Any()

    fun start(request: DocumentTranslationRequest) {
        val next = synchronized(lock) {
            job?.cancel()
            val current = Any().also { generation = it }
            val publish: (() -> Unit) -> Unit = { action -> synchronized(lock) {
                if (current === generation) action()
            } }
            fun publishProgress(value: DocumentTranslationProgress?): Boolean = synchronized(lock) {
                if (current !== generation) false else { onProgress(value); true }
            }
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val output = translate(request) { progress -> publishProgress(progress) }
                    if (publishProgress(null)) onCompleted(output, publish)
                } catch (error: CancellationException) {
                    publishProgress(null)
                    throw error
                } catch (error: DocumentTranslationException) {
                    if (publishProgress(null)) onFailed(error.message, publish)
                } catch (error: Exception) {
                    if (publishProgress(null)) onFailed(error.message ?: "Document translation failed.", publish)
                } finally {
                    synchronized(lock) { if (current === generation) job = null }
                }
            }.also { job = it }
        }
        next.start()
    }

    fun cancel() = synchronized(lock) {
        generation = Any()
        job?.cancel(CancellationException("Document translation cancelled"))
        job = null
        onProgress(null)
    }
}
