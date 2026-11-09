package com.github.ahatem.qtranslate.core.writing

import com.github.ahatem.qtranslate.api.plugin.ServiceError

sealed interface WritingError {
    data class NoService(val capability: String) : WritingError
    data class Provider(val summary: String) : WritingError
    data object Timeout : WritingError
    data object Cancelled : WritingError
    data class Unexpected(val message: String) : WritingError
}

fun ServiceError.toWritingError(): WritingError = WritingError.Provider(shortSummarySafe())

private fun ServiceError.shortSummarySafe(): String = this::class.simpleName ?: "error"
