package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.shared.notification.AppNotification
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.updater.Updater
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException

class CheckForUpdatesUseCase(
    private val settingsState: StateFlow<Configuration>,
    private val updater: Updater,
    private val notificationBus: NotificationBus
) {

    @OptIn(ExperimentalSerializationApi::class)
    suspend operator fun invoke(
        onStatusUpdate: suspend (message: String, type: NotificationType) -> Unit,
        force: Boolean = false
    ) {
        val currentSettings = settingsState.value

        if (!force && !currentSettings.autoCheckForUpdates) return

        updater.getLatestVersionInfo().onSuccess { versionInfo ->
            notificationBus.post(
                AppNotification(
                    title = "Update Available",
                    body = "Version ${versionInfo.releaseName} is now available.",
                    type = NotificationType.INFO,
                    sourcePluginId = "QTranslateApp"
                )
            )
        }.onFailure { error ->
            val displayMessage = when (error) {
                is java.net.UnknownHostException -> "Please check your internet connection and try again"
                is java.net.SocketTimeoutException -> "Connection timed out. Please try again later"
                is java.io.IOException -> "Network error occurred. Please check your connection"
                is MissingFieldException -> "Required data is missing. Please try again"
                is SerializationException -> "Invalid data received from server. Please try again"
                else -> when (error.cause) {
                    is MissingFieldException -> "Required data is missing. Please try again"
                    else -> "Unexpected error occurred. Please try again later"
                }
            }
            onStatusUpdate(displayMessage, NotificationType.ERROR)
        }

    }
}