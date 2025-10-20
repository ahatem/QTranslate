package com.github.ahatem.qtranslate.core.plugin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

class PluginKeyValueStore(private val appDataDirectory: File) {
    private val dataStores = mutableMapOf<String, DataStore<Preferences>>()

    private fun getDataStore(pluginId: String): DataStore<Preferences> {
        return dataStores.getOrPut(pluginId) {
            PreferenceDataStoreFactory.create(
                produceFile = { File(appDataDirectory, "datastore/plugin_$pluginId.preferences_pb") }
            )
        }
    }

    suspend fun getValue(pluginId: String, key: String): String? {
        return getDataStore(pluginId).data.map { it[stringPreferencesKey(key)] }.first()
    }

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

    suspend fun deleteAllData(pluginId: String) {
        getDataStore(pluginId).edit { it.clear() }
    }
}