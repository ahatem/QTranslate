package com.github.ahatem.qtranslate.api.summarizer

import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceOption
import com.github.ahatem.qtranslate.api.plugin.StandardOptions
import com.github.michaelbull.result.Result

/**
 * Condenses text while preserving its key points.
 *
 * A summarizer works within one language — it does not translate. Language support is declared
 * through [Service.supportedLanguages]; most implementations use
 * [com.github.ahatem.qtranslate.api.plugin.SupportedLanguages.All] because the underlying API
 * handles languages itself.
 *
 * ### Choosing how much to condense
 * Length is a [ServiceOption], not a fixed enum. A service that offers the conventional short,
 * medium and long choices declares [StandardOptions.SUMMARY_LENGTH] so the host can localize
 * the labels. A service with something else to offer — "one sentence", "bullet points",
 * "abstract" — declares its own option and the host renders that instead. Declaring no length
 * option at all is valid and tells the host not to show the control.
 */
interface Summarizer : Service {

    /**
     * Summarizes the text in [request].
     *
     * @return `Ok` with a [SummarizeResponse], or `Err` with a [ServiceError].
     */
    suspend fun summarize(request: SummarizeRequest): Result<SummarizeResponse, ServiceError>
}

/**
 * Parameters for a summarization.
 *
 * @property text The source text. Must not be blank.
 * @property options Chosen values keyed by [ServiceOption.key], holding
 *   [com.github.ahatem.qtranslate.api.plugin.ServiceOptionValue.id] values. Absent keys mean the
 *   service should use its own default.
 */
data class SummarizeRequest(
    val text: String,
    val options: Map<String, String> = emptyMap()
) {
    init {
        require(text.isNotBlank()) { "Summarize request text must not be blank." }
    }

    /**
     * The selected summary length id, or `null` when the user has expressed no preference.
     * Convenience for the common case of a service using [StandardOptions.SUMMARY_LENGTH].
     */
    val length: String? get() = options[StandardOptions.KEY_SUMMARY_LENGTH]
}

/**
 * The result of a summarization.
 *
 * @property summary The condensed text.
 */
data class SummarizeResponse(
    val summary: String
)
