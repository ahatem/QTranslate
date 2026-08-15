package com.github.ahatem.qtranslate.plugins.common

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.SecretStore
import com.github.ahatem.qtranslate.api.plugin.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Files

/**
 * A [PluginContext] for tests, with everything held in memory.
 *
 * Storage actually stores, rather than discarding writes and returning null: a plugin that saves a
 * setting and reads it back is doing something worth asserting, and a context that quietly forgets
 * makes that test pass for the wrong reason.
 *
 * Notifications are recorded rather than ignored, so a test can check that the user was told about
 * something without the plugin needing a callback it would not otherwise have.
 */
class FakePluginContext(
    override val logger: Logger = SilentLogger,
    private val dataDirectory: File = Files.createTempDirectory("qtranslate-plugin-test").toFile()
) : PluginContext {

    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val settings: SettingsStore = InMemorySettingsStore()
    override val secrets: SecretStore = InMemorySecretStore()

    /** Every notification the plugin raised, oldest first. */
    val notifications: List<Notification> get() = _notifications
    private val _notifications = mutableListOf<Notification>()

    data class Notification(val title: DisplayText, val body: DisplayText, val type: NotificationType)

    override suspend fun notify(title: DisplayText, body: DisplayText, type: NotificationType) {
        _notifications += Notification(title, body, type)
    }

    override fun getPluginDataDirectory(): File = dataDirectory.also { it.mkdirs() }

    object SilentLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
}

/**
 * Values are keyed by service as well as name, and a read for a service falls back to the
 * plugin-wide value — the same rule the real store follows, so a test that relies on the fallback
 * is testing the behaviour the plugin will actually get.
 */
private class InMemorySettingsStore : SettingsStore {
    private val values = mutableMapOf<String, String>()

    private fun key(name: String, serviceKey: String?) = "${serviceKey ?: "_plugin"}/$name"

    override suspend fun getString(key: String, default: String?, serviceKey: String?): String? {
        serviceKey?.let { service -> values[key(key, service)]?.let { return it } }
        return values[key(key, null)] ?: default
    }

    override suspend fun getInt(key: String, default: Int, serviceKey: String?): Int =
        getString(key, null, serviceKey)?.toIntOrNull() ?: default

    override suspend fun getLong(key: String, default: Long, serviceKey: String?): Long =
        getString(key, null, serviceKey)?.toLongOrNull() ?: default

    override suspend fun getDouble(key: String, default: Double, serviceKey: String?): Double =
        getString(key, null, serviceKey)?.toDoubleOrNull() ?: default

    override suspend fun getBoolean(key: String, default: Boolean, serviceKey: String?): Boolean =
        getString(key, null, serviceKey)?.toBooleanStrictOrNull() ?: default

    override suspend fun put(key: String, value: String, serviceKey: String?) {
        values[key(key, serviceKey)] = value
    }

    override suspend fun put(key: String, value: Int, serviceKey: String?) = put(key, value.toString(), serviceKey)
    override suspend fun put(key: String, value: Long, serviceKey: String?) = put(key, value.toString(), serviceKey)
    override suspend fun put(key: String, value: Double, serviceKey: String?) = put(key, value.toString(), serviceKey)
    override suspend fun put(key: String, value: Boolean, serviceKey: String?) = put(key, value.toString(), serviceKey)

    override suspend fun remove(key: String, serviceKey: String?) {
        values.remove(key(key, serviceKey))
    }
}

private class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()

    override suspend fun get(key: String): String? = values[key]
    override suspend fun put(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
    override suspend fun contains(key: String): Boolean = key in values
}
