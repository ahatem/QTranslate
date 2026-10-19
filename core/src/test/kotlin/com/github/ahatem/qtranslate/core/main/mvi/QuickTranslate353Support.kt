package com.github.ahatem.qtranslate.core.main.mvi

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

class RecordingTranslator : Translator {
    override val key = "t"
    override val name = key
    override val version = "test"
    override val supportedLanguages = SupportedLanguages.All
    val received = mutableListOf<String>()

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        received += request.text
        return Ok(TranslationResponse("$key:${request.text}"))
    }
}

object Silent353Logger : Logger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String, error: Throwable?) = Unit
}

object Silent353Factory : LoggerFactory {
    override fun getLogger(name: String): Logger = Silent353Logger
}
