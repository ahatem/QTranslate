package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.StandardOptions
import com.github.ahatem.qtranslate.api.summarizer.SummarizeRequest
import com.github.ahatem.qtranslate.api.summarizer.Summarizer
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.withTimeoutOrNull
import com.github.ahatem.qtranslate.core.shared.util.shortSummary

class SummarizeUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("SummarizeUseCase")

    suspend operator fun invoke(
        text: String,
        summaryLength: String,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit
    ): ExtraOutputResult {
        val summarizer = activeServiceManager.getActiveService<Summarizer>(ServiceRole.SUMMARIZER)
        if (summarizer == null) {
            logger.warn("No summarizer service available")
            onStatusUpdate(StatusCode.NoSummarizerActive, NotificationType.WARNING, true)
            return ExtraOutputResult.Failure(ExtraOutputFailure.NoSummarizer)
        }

        onStatusUpdate(StatusCode.Summarizing, NotificationType.INFO, false)

        val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            summarizer.summarize(
                SummarizeRequest(
                    text = text,
                    // The service declares which lengths it offers; the host only passes the id
                    // the user picked, so a plugin-defined length travels through unchanged.
                    options = mapOf(StandardOptions.KEY_SUMMARY_LENGTH to summaryLength)
                )
            )
        }

        if (result == null) {
            logger.error("Summarize timed out")
            onStatusUpdate(StatusCode.SummarizeTimeout, NotificationType.ERROR, true)
            return ExtraOutputResult.Failure(ExtraOutputFailure.Timeout)
        }

        return result.fold(
            success = { response ->
                logger.debug("Summarize successful")
                onStatusUpdate(StatusCode.SummaryReady, NotificationType.SUCCESS, true)
                ExtraOutputResult.Success(response.summary)
            },
            failure = { error ->
                logger.error("Summarize failed: ${error::class.simpleName}")
                val summary = error.shortSummary()
                onStatusUpdate(StatusCode.SummarizeFailed(summary), NotificationType.ERROR, true)
                ExtraOutputResult.Failure(ExtraOutputFailure.Provider(summary))
            }
        )
    }
}
