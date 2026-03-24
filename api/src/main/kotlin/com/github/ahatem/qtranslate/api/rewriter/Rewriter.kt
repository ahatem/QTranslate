package com.github.ahatem.qtranslate.api.rewriter

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * A service that rewrites text in a different style or tone while preserving its meaning.
 *
 * `Rewriter` operates within the same language as the input — it does not translate.
 * Language support is declared via [Service.supportedLanguages] as with all services.
 * Most implementations will use [com.github.ahatem.qtranslate.api.plugin.SupportedLanguages.All]
 * since language handling is delegated to the underlying API.
 */
interface Rewriter : Service {

    /**
     * Rewrites the text in [request] according to the requested [RewriteStyle].
     *
     * @param request The rewrite parameters including text and target style.
     * @return `Ok` with the [RewriteResponse] on success, or an `Err` with a [ServiceError].
     */
    suspend fun rewrite(request: RewriteRequest): Result<RewriteResponse, ServiceError>
}

/**
 * Parameters for a rewrite operation.
 *
 * @param text  The source text to rewrite. Must not be blank.
 * @param style The target style for the output. Defaults to [RewriteStyle.FORMAL].
 */
data class RewriteRequest(
    val text: String,
    val style: RewriteStyle = RewriteStyle.FORMAL
) {
    init {
        require(text.isNotBlank()) { "Rewrite request text must not be blank." }
    }
}

/**
 * The result of a successful rewrite operation.
 *
 * @param rewrittenText The rewritten version of the input text.
 * @param alternatives  Optional list of alternative rewrites if the service can produce
 *                      more than one. Empty when not supported.
 */
data class RewriteResponse(
    val rewrittenText: String,
    val alternatives: List<String> = emptyList()
)

/**
 * The target style for a rewrite operation.
 */
enum class RewriteStyle {
    /** More professional and polished. */
    FORMAL,

    /** Simpler, conversational language. */
    CASUAL,

    /** Direct and brief — removes filler words and redundancy. */
    CONCISE,

    /** Thorough and detailed — expands on the original. */
    DETAILED,

    /** Plain language — easier to read and understand. */
    SIMPLIFIED
}