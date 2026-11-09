package com.github.ahatem.qtranslate.core.plugin.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Persistent key-value storage scoped per plugin.
 *
 * Each plugin gets its own isolated DataStore file under `datastore/plugin_{id}.preferences_pb`.
 * Keys within a plugin's store are private to that plugin — there is no cross-plugin access.
 *
 * This is the backing store for [com.github.ahatem.qtranslate.core.plugin.ScopedPluginContext]'s
 * `storeValue` / `getValue` / `deleteValue` methods.
 */
class PluginKeyValueStore(
    private val appDataDirectory: File,
    val storageScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val dataStores = mutableMapOf<String, DataStore<Preferences>>()

    @Synchronized
    private fun getDataStore(pluginId: String): DataStore<Preferences> =
        dataStores.getOrPut(pluginId) {
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(storageScope.coroutineContext + Dispatchers.IO),
                produceFile = { File(appDataDirectory, "datastore/plugin_$pluginId.preferences_pb") }
            )
        }

    suspend fun getValue(pluginId: String, key: String): String? =
        getDataStore(pluginId).data.map { it[stringPreferencesKey(key)] }.first()

    suspend fun storeValue(pluginId: String, key: String, value: String) {
        getDataStore(pluginId).edit { it[stringPreferencesKey(key)] = value }
    }

    suspend fun storeValues(pluginId: String, values: Map<String, String>) {
        getDataStore(pluginId).edit { preferences ->
            values.forEach { (key, value) ->
                preferences[stringPreferencesKey(key)] = value
            }
        }
    }

    suspend fun deleteValue(pluginId: String, key: String) {
        getDataStore(pluginId).edit { it.remove(stringPreferencesKey(key)) }
    }

    suspend fun deleteAllData(pluginId: String) {
        // Keep the cached DataStore: dropping it would leave its scope alive while a recreated
        // instance opens the same file (androidx rejects two active DataStores per file), which
        // is exactly what an uninstall followed by a reinstall of the same plugin does.
        getDataStore(pluginId).edit { it.clear() }
    }
}
