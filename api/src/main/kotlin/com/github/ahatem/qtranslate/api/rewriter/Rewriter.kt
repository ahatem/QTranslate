package com.github.ahatem.qtranslate.api.rewriter

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceOption
import com.github.ahatem.qtranslate.api.plugin.StandardOptions
import com.github.michaelbull.result.Result

/**
 * Rewrites text in a different style or tone while preserving its meaning.
 *
 * A rewriter works within one language — it does not translate. Language support is declared
 * through [Service.supportedLanguages]; most implementations use
 * [com.github.ahatem.qtranslate.api.plugin.SupportedLanguages.All] because the underlying API
 * handles languages itself.
 *
 * ### Choosing a style
 * Style is a [ServiceOption], not a fixed enum. A service offering the conventional formal,
 * casual, concise, detailed and simplified choices declares [StandardOptions.REWRITE_STYLE] so
 * the host can localize the labels. A service that can do "academic", "for a five-year-old" or
 * "bullet points" declares its own option and the host renders that instead — previously
 * impossible, since the host owned the vocabulary and offered every value regardless of what
 * the service actually supported.
 */
public interface Rewriter : Service {

    /**
     * Rewrites the text in [request].
     *
     * @return `Ok` with a [RewriteResponse], or `Err` with a [ServiceError].
     */
    public suspend fun rewrite(request: RewriteRequest): Result<RewriteResponse, ServiceError>
}

/**
 * Parameters for a rewrite.
 *
 * @property text The source text. Must not be blank.
 * @property options Chosen values keyed by [ServiceOption.key], holding
 *   [com.github.ahatem.qtranslate.api.plugin.ServiceOptionValue.id] values. Absent keys mean the
 *   service should use its own default.
 */
public data class RewriteRequest(
    val text: String,
    val options: Map<String, String> = emptyMap()
) {
    init {
        require(text.isNotBlank()) { "Rewrite request text must not be blank." }
    }

    /**
     * The selected style id, or `null` when the user has expressed no preference.
     * Convenience for the common case of a service using [StandardOptions.REWRITE_STYLE].
     */
    val style: String? get() = options[StandardOptions.KEY_REWRITE_STYLE]
}

/**
 * The result of a rewrite.
 *
 * @property rewrittenText The rewritten text.
 * @property alternatives Further rewrites, when the service can produce more than one. Empty
 *   when unsupported.
 */
public data class RewriteResponse(
    val rewrittenText: String,
    val alternatives: List<String> = emptyList()
)
