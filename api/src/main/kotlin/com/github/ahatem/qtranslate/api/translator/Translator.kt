package com.github.ahatem.qtranslate.api.translator

import com.github.ahatem.qtranslate.api.LanguageCode
import com.github.ahatem.qtranslate.api.LanguageSupport
import com.github.ahatem.qtranslate.api.Service
import com.github.ahatem.qtranslate.api.ServiceError
import com.github.michaelbull.result.Result

/**
 * A service that translates text.
 */
interface Translator : Service, LanguageSupport {
    suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError>
}

data class TranslationRequest(
    val text: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode
)

data class TranslationResponse(
    val translatedText: String,
    val detectedLanguage: LanguageCode? = null,
    val alternatives: List<String> = emptyList(),

    val transliteration: String? = null
)