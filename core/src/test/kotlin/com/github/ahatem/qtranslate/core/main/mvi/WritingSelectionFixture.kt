package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.rewriter.RewriteRequest
import com.github.ahatem.qtranslate.api.rewriter.RewriteResponse
import com.github.ahatem.qtranslate.api.rewriter.Rewriter
import com.github.ahatem.qtranslate.api.summarizer.SummarizeRequest
import com.github.ahatem.qtranslate.api.summarizer.SummarizeResponse
import com.github.ahatem.qtranslate.api.summarizer.Summarizer
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.Err
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** A [MainStoreFixture] with recording Rewriter and Summarizer providers. */
internal class WritingSelectionFixture(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    includeRewriter: Boolean = true,
    includeSummarizer: Boolean = true,
    /** When false the event channel is left unread so a test can buffer deliveries first. */
    collectEvents: Boolean = true,
) {
    val rewriter = RecordingRewriter()
    val summarizer = RecordingSummarizer()
    private val main = MainStoreFixture(
        services = buildMap<String, Service> {
            if (includeRewriter) put(rewriter.key, rewriter)
            if (includeSummarizer) put(summarizer.key, summarizer)
        },
        configuration = MainStoreFixture.configuration(),
        dispatcher = dispatcher,
        collectEvents = collectEvents,
    )

    val settings get() = main.settings
    val events get() = main.events
    val store get() = main.store

    suspend fun awaitEvent(predicate: (MainEvent) -> Boolean): MainEvent = main.awaitEvent(predicate)

    fun cleanUp() = main.cleanUp()
}

/** A provider that records calls and can be scripted to succeed, fail, throw, or never answer. */
internal abstract class ScriptedWriter<Request, Response>(
    key: String,
    defaultText: String,
    private val response: (String) -> Response,
) : RecordingService(key) {
    val calls = mutableListOf<Request>()
    val started = CompletableDeferred<Unit>()
    var release: CompletableDeferred<Unit>? = null
    var throws: Boolean = false
    private var result: Result<Response, ServiceError> = Ok(response(defaultText))

    fun answerWith(text: String) {
        result = Ok(response(text))
    }

    fun failWith(error: ServiceError) {
        result = Err(error)
    }

    protected suspend fun answer(request: Request): Result<Response, ServiceError> {
        calls += request
        started.complete(Unit)
        release?.await()
        if (throws) throw IllegalStateException("provider exploded")
        return result
    }
}

internal class RecordingRewriter : ScriptedWriter<RewriteRequest, RewriteResponse>("rewriter", "rewritten text", { RewriteResponse(it) }), Rewriter {
    override suspend fun rewrite(request: RewriteRequest) = answer(request)
}

internal class RecordingSummarizer : ScriptedWriter<SummarizeRequest, SummarizeResponse>("summarizer", "summary text", { SummarizeResponse(it) }), Summarizer {
    override suspend fun summarize(request: SummarizeRequest) = answer(request)
}

internal abstract class RecordingService(override val key: String) : Service {
    override val name = key
    override val version = "test"
    override val supportedLanguages = SupportedLanguages.All
}
