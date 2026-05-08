package com.github.ahatem.qtranslate.core.shared.notification

import com.github.ahatem.qtranslate.api.plugin.NotificationType

data class AppNotification(
    val type: NotificationType,
    val code: NotificationCode,
    val sourcePluginId: String? = null,
    /** Wall-clock time when this notification was posted, used to discard stale replays. */
    val timestamp: Long = System.currentTimeMillis()
)
