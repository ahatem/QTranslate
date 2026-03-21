package com.github.ahatem.qtranslate.core.plugin.storage

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

/**
 * A persisted record of a plugin's identity and JAR integrity hash from the last successful run.
 */
@Serializable
data class PluginFingerprint(
    val id: String,
    val jarHash: String
)

/**
 * Persists [PluginFingerprint]s across application runs.
 *
 * On startup, the [com.github.ahatem.qtranslate.core.plugin.PluginManager] compares
 * each plugin JAR's current SHA-256 hash against the stored fingerprint. A mismatch
 * means the JAR was replaced or modified outside the application — the plugin is
 * paused in [com.github.ahatem.qtranslate.core.plugin.PluginStatus.AWAITING_VERIFICATION]
 * until the user confirms the change.
 *
 * On shutdown, fingerprints are saved for all plugins that ended in a healthy state
 * (i.e. not [com.github.ahatem.qtranslate.core.plugin.PluginStatus.FAILED] or
 * [com.github.ahatem.qtranslate.core.plugin.PluginStatus.AWAITING_VERIFICATION]).
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
            ?: return emptyMap()
        return runCatching {
            json.decodeFromString<List<PluginFingerprint>>(jsonString)
                .associate { it.id to it.jarHash }
        }.getOrElse { emptyMap() }
    }

    suspend fun storeFingerprints(fingerprints: List<PluginFingerprint>) {
        val jsonString = json.encodeToString(fingerprints)
        dataStore.edit { it[Keys.REGISTRY_JSON] = jsonString }
    }
}
