package com.github.ahatem.qtranslate.core.plugin.storage

import com.github.ahatem.qtranslate.api.plugin.SecretStore
import com.github.ahatem.qtranslate.api.plugin.SettingsStore

/**
 * Where a plugin's values live.
 *
 * The backing store is a flat string map per plugin, so the scope is folded into the key:
 *
 * ```
 * <instanceId>/_plugin/<key>      plugin-wide — endpoint, credentials, shared options
 * <instanceId>/<serviceKey>/<key> one service's override of a plugin-wide value
 * ```
 *
 * The instance segment is always present even though only one instance exists today. Adding it
 * later would mean migrating every stored key; adding it now costs nothing and the segment simply
 * reads `default` until multiple instances arrive.
 */
private const val PLUGIN_WIDE = "_plugin"

private fun scopedKey(instanceId: String, serviceKey: String?, key: String): String =
    "$instanceId/${serviceKey ?: PLUGIN_WIDE}/$key"

/**
 * A plugin's settings, scoped to one instance of it.
 *
 * Reads for a specific service fall back to the plugin-wide value, so a plugin that declares one
 * settings class for everything it offers keeps working unchanged, while one that wants a
 * different model per service can override just that key.
 *
 * Values are held as strings and parsed on read. A value that will not parse — hand-edited, or
 * left over from a type that changed — yields the default rather than throwing, since a plugin
 * cannot do anything useful with an exception from a settings read.
 */
internal class ScopedSettingsStore(
    private val pluginId: String,
    private val instanceId: String,
    private val store: PluginKeyValueStore
) : SettingsStore {

    override suspend fun getString(key: String, default: String?, serviceKey: String?): String? {
        serviceKey?.let { service ->
            store.getValue(pluginId, scopedKey(instanceId, service, key))?.let { return it }
        }
        return store.getValue(pluginId, scopedKey(instanceId, null, key)) ?: default
    }

    override suspend fun getInt(key: String, default: Int, serviceKey: String?): Int =
        getString(key, null, serviceKey)?.toIntOrNull() ?: default

    override suspend fun getLong(key: String, default: Long, serviceKey: String?): Long =
        getString(key, null, serviceKey)?.toLongOrNull() ?: default

    override suspend fun getDouble(key: String, default: Double, serviceKey: String?): Double =
        getString(key, null, serviceKey)?.toDoubleOrNull() ?: default

    override suspend fun getBoolean(key: String, default: Boolean, serviceKey: String?): Boolean =
        getString(key, null, serviceKey)?.toBooleanStrictOrNull() ?: default

    override suspend fun put(key: String, value: String, serviceKey: String?) =
        store.storeValue(pluginId, scopedKey(instanceId, serviceKey, key), value)

    override suspend fun put(key: String, value: Int, serviceKey: String?) =
        put(key, value.toString(), serviceKey)

    override suspend fun put(key: String, value: Long, serviceKey: String?) =
        put(key, value.toString(), serviceKey)

    override suspend fun put(key: String, value: Double, serviceKey: String?) =
        put(key, value.toString(), serviceKey)

    override suspend fun put(key: String, value: Boolean, serviceKey: String?) =
        put(key, value.toString(), serviceKey)

    override suspend fun remove(key: String, serviceKey: String?) =
        store.deleteValue(pluginId, scopedKey(instanceId, serviceKey, key))
}

/**
 * A plugin's credentials, scoped to one instance of it.
 *
 * Kept in a separate file from [ScopedSettingsStore] rather than a reserved key prefix, so that
 * settings can be exported, logged or reset without carrying credentials along, and so the
 * backing store can be swapped for the OS keychain without touching the settings path.
 *
 * **These are not encrypted yet.** The separation is what makes encrypting them later a change to
 * this class alone; until then the contents sit on disk in the same form settings do, and the
 * threat model is no better than the file permissions of the app data directory.
 */
internal class ScopedSecretStore(
    pluginId: String,
    private val instanceId: String,
    private val store: PluginKeyValueStore
) : SecretStore {

    // A distinct store id, which the backing implementation turns into its own file.
    private val secretsId = "$pluginId.secrets"

    private fun key(key: String) = "$instanceId/$key"

    override suspend fun get(key: String): String? = store.getValue(secretsId, key(key))

    override suspend fun put(key: String, value: String) =
        store.storeValue(secretsId, key(key), value)

    override suspend fun remove(key: String) = store.deleteValue(secretsId, key(key))

    override suspend fun contains(key: String): Boolean = get(key) != null
}
