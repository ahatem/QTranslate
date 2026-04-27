package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.summarizer.SummarizeRequest
import com.github.ahatem.qtranslate.api.summarizer.Summarizer
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.withTimeoutOrNull

class SummarizeUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("SummarizeUseCase")

    suspend operator fun invoke(
        text: String,
        config: Configuration,
        onStatusUpdate: suspend (message: String, type: NotificationType, isTemporary: Boolean) -> Unit
    ): String {
        val summarizer = activeServiceManager.getActiveService<Summarizer>(ServiceType.SUMMARIZER)
        if (summarizer == null) {
            logger.warn("No summarizer service available")
            onStatusUpdate("No summarizer service is active. Install and configure a summarizer plugin.", NotificationType.WARNING, true)
            return ""
        }

        onStatusUpdate("Summarizing...", NotificationType.INFO, false)

        val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            summarizer.summarize(
                SummarizeRequest(
                    text   = text,
                    length = config.summaryLength
                )
            )
        }

        if (result == null) {
            logger.error("Summarize timed out")
            onStatusUpdate("Summarize timed out.", NotificationType.ERROR, true)
            return ""
        }

        return result.fold(
            success = { response ->
                logger.debug("Summarize successful")
                onStatusUpdate("Summary ready.", NotificationType.SUCCESS, true)
                response.summary
            },
            failure = { error ->
                logger.error("Summarize failed: ${error.message}", error.cause)
                onStatusUpdate("Summarize failed: ${error.message?.lines()?.firstOrNull()?.take(120)}", NotificationType.ERROR, true)
                ""
            }
        )
    }
}