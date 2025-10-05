package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.ServiceError
import com.github.michaelbull.result.Result

/**
 * Parses API responses into a specific response type.
 */
interface ResponseParser<T> {
    suspend fun parse(jsonString: String): Result<T, ServiceError>
}