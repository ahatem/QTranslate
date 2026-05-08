package com.github.ahatem.qtranslate.core.shared.notification

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter

class NotificationBus {
    private val _notifications = MutableSharedFlow<AppNotification>(replay = 10, extraBufferCapacity = 5)

    /**
     * Stream of application notifications.
     *
     * Replayed entries older than [REPLAY_FRESHNESS_MS] are automatically discarded so
     * that subscribers who join after a UI rebuild (e.g. after a theme change) do not
     * see stale notifications from previous sessions.
     */
    val notifications: Flow<AppNotification> = _notifications
        .asSharedFlow()
        .filter { System.currentTimeMillis() - it.timestamp < REPLAY_FRESHNESS_MS }

    suspend fun post(notification: AppNotification) {
        _notifications.emit(notification)
    }

    companion object {
        /** Notifications older than this are silently dropped on replay. */
        private const val REPLAY_FRESHNESS_MS = 5_000L
    }
}