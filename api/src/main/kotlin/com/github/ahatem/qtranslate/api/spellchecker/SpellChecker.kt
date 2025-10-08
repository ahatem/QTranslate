package com.github.ahatem.qtranslate.api.spellchecker

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.language.LanguageSupport
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * A service that checks for spelling and grammar mistakes.
 */
interface SpellChecker : Service, LanguageSupport {
    suspend fun check(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError>
}

data class SpellCheckRequest(
    val text: String,
    val language: LanguageCode = LanguageCode.AUTO
)

data class SpellCheckResponse(
    val correctedText: String,
    val corrections: List<Correction>
)

data class Correction(
    val original: String,
    val startIndex: Int,
    val endIndex: Int,
    val suggestions: List<String>,
    val type: CorrectionType = CorrectionType.SPELLING,
    val message: String? = null
)

