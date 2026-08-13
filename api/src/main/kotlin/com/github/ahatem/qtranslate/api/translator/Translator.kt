package com.github.ahatem.qtranslate.api.translator

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * A service that translates text from one language to another.
 *
 * Language support is declared via [Service.supportedLanguages]. The presence of
 * [LanguageCode.AUTO] in a [com.github.ahatem.qtranslate.api.plugin.SupportedLanguages.Specific]
 * set, or using [com.github.ahatem.qtranslate.api.plugin.SupportedLanguages.All], indicates
 * that auto-detection of the source language is supported.
 */
interface Translator : Service {

    /**
     * Translates the text in [request] from the source language to the target language.
     *
     * If [TranslationRequest.sourceLanguage] is [LanguageCode.AUTO] and the service
     * supports auto-detection, the detected language should be reported back in
     * [TranslationResponse.detectedLanguage].
     *
     * @param request The translation parameters including text and language pair.
     * @return `Ok` with the [TranslationResponse] on success, or an `Err` with a [ServiceError].
     */
    suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError>
}

/**
 * Optional translator capability for providers that can process multiple independent
 * text segments in one request. The core discovers this through
 * [Service.getCapability] and falls back to [Translator.translate] when absent.
 *
 * Implementations must preserve input order and return exactly one response per input.
 * Empty strings are intentionally rejected so result indexes always map unambiguously.
 */
interface BatchTranslator : Translator {
    /** Maximum items accepted by one provider request. */
    val maxBatchSize: Int
        get() = 50

    /** Conservative character limit for one provider request. */
    val maxBatchCharacters: Int
        get() = 100_000

    /**
     * Translates the ordered text segments in [request] as one provider operation.
     *
     * @return one translation for every input segment in the same order, or a service error.
     */
    suspend fun translateBatch(
        request: BatchTranslationRequest
    ): Result<BatchTranslationResponse, ServiceError>
}

/**
 * Parameters for an ordered batch translation operation.
 *
 * @param texts non-blank independent segments whose order must be preserved.
 * @param sourceLanguage source language, or [LanguageCode.AUTO] for provider detection.
 * @param targetLanguage target language; must not be [LanguageCode.AUTO].
 */
data class BatchTranslationRequest(
    val texts: List<String>,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode
) {
    init {
        require(texts.isNotEmpty()) { "Batch translation request must contain at least one text." }
        require(texts.none(String::isBlank)) { "Batch translation request texts must not be blank." }
        require(targetLanguage != LanguageCode.AUTO) {
            "Target language must be a specific language, not AUTO."
        }
    }
}

/**
 * Result of a batch translation operation.
 *
 * [translations] must contain one item for every requested segment in the same order.
 */
data class BatchTranslationResponse(
    val translations: List<TranslationResponse>
) {
    init {
        require(translations.isNotEmpty()) { "Batch translation response must not be empty." }
    }
}

/**
 * Parameters for a translation operation.
 *
 * @param text           The source text to translate. Must not be blank.
 * @param sourceLanguage The language of [text]. Use [LanguageCode.AUTO] to request
 *                       auto-detection (only valid if the service supports it).
 * @param targetLanguage The language to translate [text] into.
 *                       Must not be [LanguageCode.AUTO].
 */
data class TranslationRequest(
    val text: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode
) {
    init {
        require(text.isNotBlank()) { "Translation request text must not be blank." }
        require(targetLanguage != LanguageCode.AUTO) {
            "Target language must be a specific language, not AUTO."
        }
    }
}

/**
 * The result of a successful translation.
 *
 * @param translatedText    The translated text in the target language.
 * @param detectedLanguage  The language detected in the source text, if auto-detection
 *                          was requested and the service supports it. `null` otherwise.
 * @param alternatives      Optional list of alternative translations for the same text.
 * @param transliteration   Optional romanisation or phonetic representation of the
 *                          translated text (e.g. pinyin for Chinese).
 */
data class TranslationResponse(
    val translatedText: String,
    val detectedLanguage: LanguageCode? = null,
    val alternatives: List<String> = emptyList(),
    val transliteration: String? = null
)
