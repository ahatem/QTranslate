package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.shared.notification.AppNotification
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * Provides a sandboxed execution context for a single plugin instance.
 *
 * Each plugin receives its own isolated `ScopedPluginContext` — storage, logging,
 * notifications, and coroutine scope are all scoped to the plugin's ID and cannot
 * leak into other plugins.
 *
 * ### Lifecycle
 * The [scope] is active for as long as the plugin is enabled. The [PluginManager]
 * calls [cancelScope] immediately after `Plugin.onDisable()` returns, cancelling all
 * coroutines the plugin launched via [scope]. A fresh scope is created each time the
 * plugin is re-enabled.
 *
 * This means plugins **must** use [scope] for all background work — using `GlobalScope`
 * will create unmanaged coroutines that survive disable/enable cycles.
 */
internal class ScopedPluginContext(
    private val pluginId: String,
    private val appDataDirectory: File,
    private val pluginKeyValueStore: PluginKeyValueStore,
    private val notificationBus: NotificationBus,
    override val logger: Logger
) : PluginContext {

    // A fresh SupervisorJob-backed scope on IO dispatcher.
    // SupervisorJob ensures one failing child coroutine doesn't cancel siblings.
    private var _scope = createFreshScope()
    override val scope: CoroutineScope get() = _scope

    private val pluginDataDir by lazy {
        File(appDataDirectory, "plugins_data/$pluginId").apply { mkdirs() }
    }

    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Key-value storage
    // -------------------------------------------------------------------------

    override suspend fun getValue(key: String): String? {
        logger.debug("[$pluginId] Reading key: $key")
        return pluginKeyValueStore.getValue(pluginId, key)
    }

    override suspend fun storeValue(key: String, value: String) {
        logger.debug("[$pluginId] Writing key: $key")
        pluginKeyValueStore.storeValue(pluginId, key, value)
    }

    override suspend fun deleteValue(key: String) {
        logger.debug("[$pluginId] Deleting key: $key")
        pluginKeyValueStore.deleteValue(pluginId, key)
    }

    // -------------------------------------------------------------------------
    // File system
    // -------------------------------------------------------------------------

    override fun getPluginDataDirectory(): File = pluginDataDir

    // -------------------------------------------------------------------------
    // Scope lifecycle — called by PluginLifecycleHandler
    // -------------------------------------------------------------------------

    /**
     * Cancels all coroutines currently running in [scope].
     * Called by the core immediately after `Plugin.onDisable()` returns.
     */
    internal fun cancelScope() {
        _scope.cancel("Plugin '$pluginId' disabled")
    }

    /**
     * Creates a fresh [CoroutineScope] for the next enable cycle.
     * Called by the core before invoking `Plugin.onEnable()`.
     */
    internal fun resetScope() {
        _scope = createFreshScope()
    }

    private fun createFreshScope() =
        CoroutineScope(Dispatchers.IO + SupervisorJob())
}
