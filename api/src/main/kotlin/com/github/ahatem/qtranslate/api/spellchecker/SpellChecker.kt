package com.github.ahatem.qtranslate.api.spellchecker

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * A service that checks text for spelling, grammar, style, and punctuation mistakes.
 *
 * Language support is declared via [Service.supportedLanguages]. The presence of
 * [LanguageCode.AUTO] in the supported set, or using
 * [com.github.ahatem.qtranslate.api.plugin.SupportedLanguages.All], indicates that
 * the service can detect the language of the input automatically.
 */
interface SpellChecker : Service {

    /**
     * Checks the text in [request] for errors and returns corrections.
     *
     * Implementations should aim to return all detected issues in a single call.
     * The [SpellCheckResponse.correctedText] should reflect all applied corrections,
     * while [SpellCheckResponse.corrections] provides the granular diff.
     *
     * @param request The text and language to check.
     * @return `Ok` with a [SpellCheckResponse] on success, or an `Err` with a [ServiceError].
     *         Returns [ServiceError.InvalidInputError] if the text is blank.
     */
    suspend fun check(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError>
}

/**
 * Parameters for a spell-check operation.
 *
 * @param text     The text to check. Must not be blank.
 * @param language The language of [text]. Defaults to [LanguageCode.AUTO] for
 *                 services that support language auto-detection.
 */
data class SpellCheckRequest(
    val text: String,
    val language: LanguageCode = LanguageCode.AUTO
) {
    init {
        require(text.isNotBlank()) { "Spell check request text must not be blank." }
    }
}

/**
 * The result of a successful spell-check operation.
 *
 * @param correctedText The full text with all corrections applied. Equal to the
 *                      input text if no corrections were found.
 * @param corrections   A list of individual corrections, each pointing to a specific
 *                      span in the original text. Empty if no issues were found.
 */
data class SpellCheckResponse(
    val correctedText: String,
    val corrections: List<Correction>
)

/**
 * A single spelling, grammar, style, or punctuation correction.
 *
 * @param original    The original (incorrect) text span.
 * @param startIndex  The start index of [original] in the source text (inclusive).
 * @param endIndex    The end index of [original] in the source text (exclusive).
 * @param suggestions A ranked list of suggested replacements (best suggestion first).
 * @param type        The category of the correction. Defaults to [CorrectionType.SPELLING].
 * @param message     An optional human-readable explanation of the issue, suitable
 *                    for display in a tooltip (e.g. `"Use 'affect' as a verb here."`).
 */
data class Correction(
    val original: String,
    val startIndex: Int,
    val endIndex: Int,
    val suggestions: List<String>,
    val type: CorrectionType = CorrectionType.SPELLING,
    val message: String? = null
) {
    init {
        require(startIndex >= 0) { "Correction startIndex must be >= 0, was $startIndex." }
        require(endIndex > startIndex) {
            "Correction endIndex ($endIndex) must be greater than startIndex ($startIndex)."
        }
    }
}