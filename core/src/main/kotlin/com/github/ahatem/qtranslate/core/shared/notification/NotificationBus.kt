package com.github.ahatem.qtranslate.core.shared.notification

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class NotificationBus {
    private val _notifications = MutableSharedFlow<AppNotification>(extraBufferCapacity = 5)
    val notifications = _notifications.asSharedFlow()

    suspend fun post(notification: AppNotification) {
        _notifications.emit(notification)
    }
}