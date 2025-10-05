package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.PluginContext
import com.github.ahatem.qtranslate.api.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes a block in the IO dispatcher, handling errors consistently.
 */
suspend fun <T> withIO(block: suspend () -> T): Result<T, ServiceError> = withContext(Dispatchers.IO) {
    try {
        Ok(block())
    } catch (e: Exception) {
        Err(ServiceError.UnknownError("Operation failed: ${e.message}", e))
    }
}

/**
 * Logs and handles errors for a specific operation.
 */
suspend fun <T> PluginContext.logAndHandleError(
    operation: String,
    block: suspend () -> T
): Result<T, ServiceError> = withContext(Dispatchers.IO) {
    try {
        Ok(block())
    } catch (e: Exception) {
        logError(e, "$operation failed")
        Err(ServiceError.UnknownError("Failed to $operation: ${e.message}", e))
    }
}