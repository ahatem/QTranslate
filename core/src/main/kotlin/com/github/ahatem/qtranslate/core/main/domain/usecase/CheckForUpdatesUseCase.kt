package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.AppNotification
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.shared.notification.NotificationCode
import com.github.ahatem.qtranslate.core.updater.Updater
import com.github.ahatem.qtranslate.core.updater.UpdaterError
import com.github.ahatem.qtranslate.core.updater.data.UpdateCheckResult
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import kotlinx.coroutines.flow.StateFlow

/**
 * Checks whether a newer version of the application is available.
 *
 * Respects the [Configuration.autoCheckForUpdates] setting unless [force] is `true`.
 * On success, posts a notification and calls [onStatusUpdate] with the result.
 * On failure, maps the typed [UpdaterError] to a user-friendly message.
 *
 * @property currentVersion The version string of the running application (e.g. `"1.2.0"`).
 *   Passed to [Updater.checkForUpdate] for semantic version comparison.
 */
class CheckForUpdatesUseCase(
    private val currentVersion: String,
    private val settingsState: StateFlow<Configuration>,
    private val updater: Updater,
    private val notificationBus: NotificationBus,
    loggerFactory: LoggerFactory
) {
    private val logger: Logger = loggerFactory.getLogger("CheckForUpdatesUseCase")

    /**
     * Performs the update check.
     *
     * @param onStatusUpdate Callback for displaying a status message in the UI.
     * @param force If `true`, skips the [Configuration.autoCheckForUpdates] guard.
     */
    suspend operator fun invoke(
        onStatusUpdate: suspend (message: String, type: NotificationType, isTemporary: Boolean) -> Unit,
        force: Boolean = false
    ) {
        if (!force && !settingsState.value.autoCheckForUpdates) {
            logger.debug("Auto-update check disabled — skipping")
            return
        }

        logger.info("Checking for updates (currentVersion=$currentVersion, force=$force)")

        updater.checkForUpdate(currentVersion)
            .onOk { result ->
                when (result) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        val info = result.info
                        logger.info("Update available: ${info.versionTag}")

                        notificationBus.post(
                            AppNotification(
                                type = NotificationType.INFO,
                                code = NotificationCode.UpdateAvailable(
                                    newVersion = info.releaseName,
                                    currentVersion = currentVersion,
                                    releaseNotes = info.releaseNotes,
                                    downloadUrl = info.downloadUrl
                                )
                            )
                        )
                    }

                    is UpdateCheckResult.AlreadyUpToDate -> {
                        logger.info("Already up to date (version: $currentVersion)")
                        onStatusUpdate(
                            "You are using the latest version ($currentVersion)",
                            NotificationType.SUCCESS,
                            true
                        )
                    }
                }
            }
            .onErr { error ->
                logger.error("Update check failed: ${error.message}", error.cause)

                val userMessage = when (error) {
                    is UpdaterError.NetworkError ->
                        "Update check failed. Please check your internet connection and try again."

                    is UpdaterError.ParseError ->
                        "Update check failed. The server returned unexpected data. Please try again later."

                    is UpdaterError.UnknownError ->
                        "Update check failed. An unexpected error occurred. Please try again later."
                }

                onStatusUpdate(userMessage, NotificationType.ERROR, true)
            }
    }
}