package com.github.ahatem.qtranslate.core.document

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** Owns the active document operation and discards updates from superseded operations. */
internal class DocumentTranslationSession(
    private val scope: CoroutineScope,
    private val translate: suspend (DocumentTranslationRequest, (DocumentTranslationProgress) -> Unit) -> File,
    private val onProgress: (DocumentTranslationProgress?) -> Unit,
    private val onCompleted: suspend (File) -> Unit,
    private val onFailed: suspend (String) -> Unit
) {
    private val lock = Any()
    private var job: Job? = null
    private var generation = 0L

    fun start(request: DocumentTranslationRequest) {
        val next = synchronized(lock) {
            job?.cancel()
            val current = ++generation
            fun publishProgress(value: DocumentTranslationProgress?): Boolean = synchronized(lock) {
                if (current != generation) false else { onProgress(value); true }
            }
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val output = translate(request) { progress -> publishProgress(progress) }
                    if (publishProgress(null)) onCompleted(output)
                } catch (error: CancellationException) {
                    publishProgress(null)
                    throw error
                } catch (error: DocumentTranslationException) {
                    if (publishProgress(null)) onFailed(error.message)
                } catch (error: Exception) {
                    if (publishProgress(null)) onFailed(error.message ?: "Document translation failed.")
                } finally {
                    synchronized(lock) { if (current == generation) job = null }
                }
            }.also { job = it }
        }
        next.start()
    }

    fun cancel() = synchronized(lock) {
        generation++
        job?.cancel(CancellationException("Document translation cancelled"))
        job = null
        onProgress(null)
    }
}
