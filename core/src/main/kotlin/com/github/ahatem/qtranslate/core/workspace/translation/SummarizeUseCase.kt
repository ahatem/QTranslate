package com.github.ahatem.qtranslate.core.workspace.translation

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.StandardOptions
import com.github.ahatem.qtranslate.api.summarizer.SummarizeRequest
import com.github.ahatem.qtranslate.api.summarizer.Summarizer
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory

class SummarizeUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("SummarizeUseCase")

    suspend operator fun invoke(
        text: String,
        summaryLength: String,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit
    ): ExtraOutputResult = runWritingRequest(
        service = activeServiceManager.getActiveService<Summarizer>(ServiceRole.SUMMARIZER),
        status = STATUS,
        logger = logger,
        onStatusUpdate = onStatusUpdate,
        // The service declares which lengths it offers; the host only passes the id the user
        // picked, so a plugin-defined length travels through unchanged.
        call = { it.summarize(SummarizeRequest(text, mapOf(StandardOptions.KEY_SUMMARY_LENGTH to summaryLength))) },
        text = { it.summary },
    )

    private companion object {
        val STATUS = WritingStatus(
            name = "Summarize",
            serviceName = "summarizer",
            missingService = StatusCode.NoSummarizerActive,
            missingFailure = ExtraOutputFailure.NoSummarizer,
            working = StatusCode.Summarizing,
            timedOut = StatusCode.SummarizeTimeout,
            ready = StatusCode.SummaryReady,
            failed = StatusCode::SummarizeFailed,
        )
    }
}
