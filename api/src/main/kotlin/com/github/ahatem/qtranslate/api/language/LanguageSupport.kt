package com.github.ahatem.qtranslate.api.language

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

interface LanguageSupport {
    /**
     * Returns the set of supported languages. The presence of LanguageCode.AUTO
     * in the returned set indicates that auto-detection is supported.
     * This is a suspend function to allow fetching the list from a remote API.
     */
    suspend fun getSupportedLanguages(): Result<Set<LanguageCode>, ServiceError>
}