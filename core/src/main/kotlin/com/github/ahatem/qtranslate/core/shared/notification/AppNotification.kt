package com.github.ahatem.qtranslate.core.shared.notification

import com.github.ahatem.qtranslate.api.plugin.NotificationType

data class AppNotification(
    val type: NotificationType,
    val title: String,
    val body: String,
    val sourcePluginId: String? = null
)
