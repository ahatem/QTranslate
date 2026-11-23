package com.github.ahatem.qtranslate.core.workspace.translation

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.StandardOptions
import com.github.ahatem.qtranslate.api.rewriter.RewriteRequest
import com.github.ahatem.qtranslate.api.rewriter.Rewriter
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.shared.StatusCode
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory

class RewriteUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger = loggerFactory.getLogger("RewriteUseCase")

    suspend operator fun invoke(
        text: String,
        rewriteStyle: String,
        onStatusUpdate: suspend (code: StatusCode, type: NotificationType, isTemporary: Boolean) -> Unit
    ): ExtraOutputResult = runWritingRequest(
        service = activeServiceManager.getActiveService<Rewriter>(ServiceRole.REWRITER),
        status = STATUS,
        logger = logger,
        onStatusUpdate = onStatusUpdate,
        // The service declares which styles it offers; the host only passes the id the user
        // picked, so a plugin-defined style travels through unchanged.
        call = { it.rewrite(RewriteRequest(text, mapOf(StandardOptions.KEY_REWRITE_STYLE to rewriteStyle))) },
        text = { it.rewrittenText },
    )

    private companion object {
        val STATUS = WritingStatus(
            name = "Rewrite",
            serviceName = "rewriter",
            missingService = StatusCode.NoRewriterActive,
            missingFailure = ExtraOutputFailure.NoRewriter,
            working = StatusCode.Rewriting,
            timedOut = StatusCode.RewriteTimeout,
            ready = StatusCode.RewriteReady,
            failed = StatusCode::RewriteFailed,
        )
    }
}
