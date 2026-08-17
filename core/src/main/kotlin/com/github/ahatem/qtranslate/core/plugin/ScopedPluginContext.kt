package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.core.plugin.http.HttpClientConfig
import com.github.ahatem.qtranslate.core.plugin.http.KtorHttpClient
import com.github.ahatem.qtranslate.api.plugin.SecretStore
import com.github.ahatem.qtranslate.api.plugin.SettingsStore
import com.github.ahatem.qtranslate.core.plugin.registry.ServiceId
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.plugin.storage.ScopedSecretStore
import com.github.ahatem.qtranslate.core.plugin.storage.ScopedSettingsStore
import com.github.ahatem.qtranslate.core.plugin.text.PluginTextResolver
import com.github.ahatem.qtranslate.core.shared.notification.AppNotification
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.github.ahatem.qtranslate.core.shared.notification.NotificationCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
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
    private val instanceId: String = ServiceId.DEFAULT_INSTANCE,
    private val appDataDirectory: File,
    pluginKeyValueStore: PluginKeyValueStore,
    private val notificationBus: NotificationBus,
    private val textResolver: PluginTextResolver,
    override val logger: Logger,
    /**
     * How the context builds its client. Only tests pass anything else, so they can observe that
     * one client is built per plugin rather than per enable, and that it is closed when the plugin
     * is finished with and not before.
     */
    /**
     * How the shared client is configured: proxy, timeouts, retries, connection caps.
     * Supplied by the host from the user's network settings, so that no plugin has to know
     * any of it exists.
     */
    httpConfig: HttpClientConfig = HttpClientConfig(),
    /**
     * Takes the config as well as the logger, so a test can observe which settings the client
     * was actually built with. Handed only the logger, the config was unobservable and the
     * wiring from the settings page down to here could not be asserted at all.
     */
    httpFactory: (Logger, HttpClientConfig) -> HttpClient = ::KtorHttpClientOf
) : PluginContext {

    // A fresh SupervisorJob-backed scope on IO dispatcher.
    // SupervisorJob ensures one failing child coroutine doesn't cancel siblings.
    private var _scope = createFreshScope()
    override val scope: CoroutineScope get() = _scope

    private val pluginDataDir by lazy {
        File(appDataDirectory, "plugins_data/$pluginId").apply { mkdirs() }
    }

    // -------------------------------------------------------------------------
    // Storage
    // -------------------------------------------------------------------------

    override val settings: SettingsStore =
        ScopedSettingsStore(pluginId, instanceId, pluginKeyValueStore)

    override val secrets: SecretStore =
        ScopedSecretStore(pluginId, instanceId, pluginKeyValueStore)

    // -------------------------------------------------------------------------
    // Network
    // -------------------------------------------------------------------------

    /**
     * One client per plugin, which is the number there were before this moved here: every plugin
     * built its own in `initialize`. Keeping the count the same means this change is about
     * ownership only, and pooling can be reconsidered on its own rather than riding along with a
     * relocation.
     *
     * Built with the plugin's own logger, so a failed request is still attributed to the plugin
     * that made it.
     */
    override val http: HttpClient = httpFactory(logger, httpConfig)

    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------

    /**
     * Resolves both strings before posting, so what reaches the notification bus is display text
     * in the user's language rather than a key the rest of the app would have to know how to
     * interpret.
     */
    override suspend fun notify(title: DisplayText, body: DisplayText, type: NotificationType) {
        notificationBus.post(
            AppNotification(
                type = type,
                code = NotificationCode.Custom(
                    textResolver.resolve(pluginId, title),
                    textResolver.resolve(pluginId, body)
                ),
                sourcePluginId = pluginId
            )
        )
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

    /**
     * Releases the connection pool. Called once the plugin is finished with, not on disable,
     * since the same context serves the next enable cycle.
     *
     * Plugins used to close the client they built themselves. Now that they are handed one, the
     * closing moved here with the ownership rather than being dropped: an unclosed pool keeps its
     * selector threads alive, which on a plugin the user uninstalls would leak for the rest of
     * the session.
     */
    internal fun closeHttp() {
        (http as? Closeable)?.close()
    }

    private fun createFreshScope() =
        CoroutineScope(Dispatchers.IO + SupervisorJob())
}

/**
 * The production client, as a named function so it can be a default factory value.
 *
 * A lambda would close over the constructor parameter and hide the config from anything trying to
 * observe it; a function reference keeps the two arguments visible in the signature.
 */
internal fun KtorHttpClientOf(logger: Logger, config: HttpClientConfig): HttpClient =
    KtorHttpClient(logger = logger, config = config)
