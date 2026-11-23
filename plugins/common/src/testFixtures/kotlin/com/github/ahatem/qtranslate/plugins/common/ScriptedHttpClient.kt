package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/** One text request a [ScriptedHttpClient] received. */
data class RecordedRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val queryParams: Map<String, Any?>
)

/**
 * A text [TextHttpClient] that answers from a script and records what it was asked.
 *
 * [respond] picks the answer for each request. The secondary constructors cover the common cases:
 * one response repeated for every request, or a queue consumed in order that fails the test when
 * it runs dry (an extra request nobody arranged is a bug, not something to answer quietly).
 */
class ScriptedHttpClient(
    private val respond: (RecordedRequest) -> Result<String, ServiceError>
) : TextHttpClient() {

    constructor(response: Result<String, ServiceError>) : this({ response })

    constructor(responses: List<Result<String, ServiceError>>) : this(queueOf(responses))

    val requests = mutableListOf<RecordedRequest>()

    val urls: List<String> get() = requests.map { it.url }
    val headers: List<Map<String, String>> get() = requests.map { it.headers }
    val bodies: List<String> get() = requests.map { it.body.orEmpty() }
    val queryParams: List<Map<String, Any?>> get() = requests.map { it.queryParams }

    val lastRequest: RecordedRequest? get() = requests.lastOrNull()
    val lastUrl: String? get() = lastRequest?.url
    val lastBody: String? get() = lastRequest?.body
    val lastQueryParams: Map<String, Any?> get() = lastRequest?.queryParams.orEmpty()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = answer(RecordedRequest("GET", url, headers, null, queryParams))

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String?,
        queryParams: Map<String, Any?>
    ): Result<String, ServiceError> = answer(RecordedRequest("POST", url, headers, body, queryParams))

    private fun answer(request: RecordedRequest): Result<String, ServiceError> {
        requests += request
        return respond(request)
    }

    private companion object {
        fun queueOf(responses: List<Result<String, ServiceError>>): (RecordedRequest) -> Result<String, ServiceError> {
            val queue = ArrayDeque(responses)
            return { request ->
                queue.removeFirstOrNull() ?: error("No scripted response left for ${request.method} ${request.url}")
            }
        }
    }
}
