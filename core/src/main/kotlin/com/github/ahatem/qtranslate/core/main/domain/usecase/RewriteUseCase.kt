package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.rewriter.RewriteRequest
import com.github.ahatem.qtranslate.api.rewriter.Rewriter
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.ahatem.qtranslate.core.shared.arch.ServiceType
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.michaelbull.result.fold
import kotlinx.coroutines.withTimeoutOrNull

class RewriteUseCase(
    private val activeServiceManager: ActiveServiceManager,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("RewriteUseCase")

    suspend operator fun invoke(
        text: String,
        config: Configuration,
        onStatusUpdate: suspend (message: String, type: NotificationType, isTemporary: Boolean) -> Unit
    ): String {
        val rewriter = activeServiceManager.getActiveService<Rewriter>(ServiceType.REWRITER)
        if (rewriter == null) {
            logger.warn("No rewriter service available")
            onStatusUpdate("No rewriter service is active. Install and configure a rewriter plugin.", NotificationType.WARNING, true)
            return ""
        }

        onStatusUpdate("Rewriting...", NotificationType.INFO, false)

        val result = withTimeoutOrNull(AppConstants.TRANSLATION_TIMEOUT_MS) {
            rewriter.rewrite(
                RewriteRequest(
                    text  = text,
                    style = config.rewriteStyle
                )
            )
        }

        if (result == null) {
            logger.error("Rewrite timed out")
            onStatusUpdate("Rewrite timed out.", NotificationType.ERROR, true)
            return ""
        }

        return result.fold(
            success = { response ->
                logger.debug("Rewrite successful")
                onStatusUpdate("Rewrite ready.", NotificationType.SUCCESS, true)
                response.rewrittenText
            },
            failure = { error ->
                logger.error("Rewrite failed: ${error.message}", error.cause)
                onStatusUpdate("Rewrite failed: ${error.message}", NotificationType.ERROR, true)
                ""
            }
        )
    }
}