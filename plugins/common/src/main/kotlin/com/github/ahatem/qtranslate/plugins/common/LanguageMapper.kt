package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.LanguageCode
import com.github.michaelbull.result.Result

/**
 * Maps QTranslate LanguageCode to provider-specific language codes.
 */
interface LanguageMapper {
    /**
     * Converts a QTranslate LanguageCode to a provider-specific code.
     */
    fun toProviderCode(code: LanguageCode): String

    /**
     * Converts a provider-specific code to a QTranslate LanguageCode.
     */
    fun fromProviderCode(providerCode: String): LanguageCode

    /**
     * Fetches supported languages dynamically or returns a static list.
     */
    suspend fun getSupportedLanguages(): Result<Set<LanguageCode>, com.github.ahatem.qtranslate.api.ServiceError>
}