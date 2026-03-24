package com.github.ahatem.qtranslate.api.summarizer

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * A service that condenses text into a shorter form while preserving its key points.
 *
 * `Summarizer` operates within the same language as the input — it does not translate.
 * Language support is declared via [Service.supportedLanguages] as with all services.
 * Most implementations will use [com.github.ahatem.qtranslate.api.plugin.SupportedLanguages.All]
 * since language handling is delegated to the underlying API.
 */
interface Summarizer : Service {

    /**
     * Summarizes the text in [request].
     *
     * @param request The summarization parameters including text and desired length.
     * @return `Ok` with the [SummarizeResponse] on success, or an `Err` with a [ServiceError].
     */
    suspend fun summarize(request: SummarizeRequest): Result<SummarizeResponse, ServiceError>
}

/**
 * Parameters for a summarization operation.
 *
 * @param text   The source text to summarize. Must not be blank.
 * @param length A hint for the desired output length. Advisory — the service may
 *               interpret this differently depending on its underlying API.
 *               Defaults to [SummaryLength.MEDIUM].
 */
data class SummarizeRequest(
    val text: String,
    val length: SummaryLength = SummaryLength.MEDIUM
) {
    init {
        require(text.isNotBlank()) { "Summarize request text must not be blank." }
    }
}

/**
 * The result of a successful summarization.
 *
 * @param summary The condensed version of the input text.
 */
data class SummarizeResponse(
    val summary: String
)

/**
 * A hint for the desired output length of a summary relative to the input.
 */
enum class SummaryLength {
    /** A single sentence or very short paragraph. */
    SHORT,

    /** A few sentences — the default. */
    MEDIUM,

    /** A longer condensed version, preserving more detail. */
    LONG
}