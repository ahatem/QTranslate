package com.github.ahatem.qtranslate.core.plugin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PluginFingerprint(
    val id: String,
    val jarHash: String
)

/**
 * Persists plugin fingerprints from the last successful run.
 * Used to detect modified or replaced plugin JARs.
 */
class PluginFingerprintRepository(
    appDataDirectory: File,
    private val json: Json
) {
    private object Keys {
        val REGISTRY_JSON = stringPreferencesKey("known_plugins_registry_json")
    }

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(appDataDirectory, "datastore/plugin_registry.preferences_pb") }
    )

    suspend fun loadFingerprints(): Map<String, String> {
        val jsonString = dataStore.data.map { it[Keys.REGISTRY_JSON] }.first()
        return if (jsonString != null) {
            runCatching {
                json.decodeFromString<List<PluginFingerprint>>(jsonString)
                    .associate { it.id to it.jarHash }
            }.getOrElse { emptyMap() }
        } else {
            emptyMap()
        }
    }

    suspend fun storeFingerprints(fingerprints: List<PluginFingerprint>) {
        val jsonString = json.encodeToString(fingerprints)
        dataStore.edit { it[Keys.REGISTRY_JSON] = jsonString }
    }
}
