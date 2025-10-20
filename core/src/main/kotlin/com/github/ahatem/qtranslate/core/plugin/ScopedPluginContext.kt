package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.core.shared.notification.AppNotification
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import java.io.File

/**
 * Provides a sandboxed execution context for a plugin.
 *
 * Each plugin gets isolated access to its own data, secrets, and notifications.
 * The sandbox is logical, not OS-enforced, but prevents overlap between plugins inside the app.
 */
internal class ScopedPluginContext(
    private val pluginId: String,
    private val appDataDirectory: File,
    private val pluginKeyValueStore: PluginKeyValueStore,
    private val notificationBus: NotificationBus,
    override val logger: Logger
) : PluginContext {

    private val pluginDataDir by lazy {
        File(appDataDirectory, "plugins_data/$pluginId").apply { mkdirs() }
    }

    override suspend fun notify(title: String, body: String, type: NotificationType) {
        notificationBus.post(
            AppNotification(
                title = title,
                body = body,
                type = type,
                sourcePluginId = pluginId
            )
        )
    }

    override suspend fun getValue(key: String): String? {
        logger.debug("Reading key: $key")
        return pluginKeyValueStore.getValue(pluginId, key)
    }

    override suspend fun storeValue(key: String, value: String) {
        logger.debug("Writing key: $key")
        pluginKeyValueStore.storeValue(pluginId, key, value)
    }

    override fun getPluginDataDirectory(): File = pluginDataDir
}

