package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.testing.SilentLogger
import com.github.ahatem.qtranslate.core.testing.SilentLoggerFactory
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class RecordingTranslator : Translator {
    override val key = "t"
    override val name = key
    override val version = "test"
    override val supportedLanguages = SupportedLanguages.All
    val received = mutableListOf<String>()
    /** Test hook for holding one request while a newer one starts. */
    var beforeTranslate: suspend (String) -> Unit = {}

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        received += request.text
        beforeTranslate(request.text)
        return Ok(TranslationResponse("$key:${request.text}"))
    }
}

// Other test files still import these names; drop them once those files use the shared logger.
internal typealias Silent353Logger = SilentLogger
internal typealias Silent353Factory = SilentLoggerFactory