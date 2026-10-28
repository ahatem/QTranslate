package com.github.ahatem.qtranslate.plugins.speech

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.speech.LiveSpeechEvent
import com.github.ahatem.qtranslate.api.speech.LiveSpeechSession
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture

/**
 * Common base for live recognition sessions built on top of JDK WebSocket.
 *
 * Implements the sequential [sendChain] required by JDK WebSocket (only one send operation
 * may be active at any time), lifecycle termination, and event flow emission.
 */
abstract class WebSocketLiveSession(
    private val serviceName: String
) : LiveSpeechSession {

    protected val channel = Channel<LiveSpeechEvent>(Channel.UNLIMITED)
    override val events: Flow<LiveSpeechEvent> = channel.receiveAsFlow()

    /**
     * Set by each listener's onOpen as well as after buildAsync returns: onOpen runs before buildAsync
     * completes, and anything sent from it through [enqueue] would otherwise be dropped.
     */
    @Volatile
    protected var socket: WebSocket? = null
    protected val ready = CompletableDeferred<Result<Unit, ServiceError>>()
    protected val closed = CompletableDeferred<Unit>()

    private var sendChain: CompletableFuture<*> = CompletableFuture.completedFuture(null)

    @Volatile
    protected var ended = false

    override fun cancel() {
        end(null)
        runCatching { socket?.abort() }
    }

    protected fun enqueue(send: (WebSocket) -> CompletableFuture<WebSocket>) {
        val ws = socket ?: return
        synchronized(this) {
            sendChain = sendChain.thenCompose { send(ws) }.exceptionally { error ->
                end(ServiceError.NetworkError("$serviceName live connection error: ${error.message}", error))
                null
            }
        }
    }

    protected open fun end(failure: ServiceError?) {
        if (ended) return
        ended = true
        failure?.let { channel.trySend(LiveSpeechEvent.Failed(it)) }
        channel.close()
        closed.complete(Unit)
    }

    protected suspend fun closeSocket() {
        withTimeoutOrNull(3_000L) { closed.await() }
        end(null)
        runCatching { socket?.sendClose(WebSocket.NORMAL_CLOSURE, "")?.await() }
    }
}
