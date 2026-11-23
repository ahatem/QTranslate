package com.github.ahatem.qtranslate.core.workspace.translation

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.util.shortSummary
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.withTimeoutOrNull

internal typealias StatusUpdate = suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit

/** The status codes and log wording one writing service reports with. */
internal class WritingStatus(
    val name: String,
    val serviceName: String,
    val missingService: StatusCode,
    val missingFailure: ExtraOutputFailure,
    val working: StatusCode,
    val timedOut: StatusCode,
    val ready: StatusCode,
    val failed: (summary: String) -> StatusCode,
)

/**
 * Runs one rewrite or summary request against [service], reporting progress through
 * [onStatusUpdate] and bounding the call by the host's translation timeout.
 */
internal suspend fun <S : Any, R> runWritingRequest(
    service: S?,
    status: WritingStatus,
    logger: Logger,
    onStatusUpdate: StatusUpdate,
    call: suspend (S) -> Result<R, ServiceError>,
    text: (R) -> String,
): ExtraOutputResult {
    if (service == null) {
        logger.warn("No ${status.serviceName} service available")
        onStatusUpdate(status.missingService, NotificationType.WARNING, true)
        return ExtraOutputResult.Failure(status.missingFailure)
    }

    onStatusUpdate(status.working, NotificationType.INFO, false)

    val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) { call(service) }
    if (result == null) {
        logger.error("${status.name} timed out")
        onStatusUpdate(status.timedOut, NotificationType.ERROR, true)
        return ExtraOutputResult.Failure(ExtraOutputFailure.Timeout)
    }

    return result.fold(
        success = { response ->
            logger.debug("${status.name} successful")
            onStatusUpdate(status.ready, NotificationType.SUCCESS, true)
            ExtraOutputResult.Success(text(response))
        },
        failure = { error ->
            logger.error("${status.name} failed: ${error::class.simpleName}")
            val summary = error.shortSummary()
            onStatusUpdate(status.failed(summary), NotificationType.ERROR, true)
            ExtraOutputResult.Failure(ExtraOutputFailure.Provider(summary))
        }
    )
}
