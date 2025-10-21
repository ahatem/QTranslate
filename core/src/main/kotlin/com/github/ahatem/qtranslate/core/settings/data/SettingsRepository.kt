package com.github.ahatem.qtranslate.core.settings.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.File

class SettingsRepository(
    appDataDirectory: File,
    private val json: Json,
    private val logger: Logger
) {
    private object Keys {
        val CONFIG_JSON = stringPreferencesKey("configuration_json")
        val DISABLED_PLUGIN_IDS = stringSetPreferencesKey("disabled_plugin_ids")
    }

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(appDataDirectory, "datastore/app_settings.preferences_pb") }
    )

    val configuration: Flow<Configuration> = dataStore.data
        .map { preferences ->
            preferences[Keys.CONFIG_JSON]?.let {
                json.decodeFromString<Configuration>(it)
            } ?: Configuration.DEFAULT
        }
        .catch {
            logger.error("Failed to read settings, using default", it)
            emit(Configuration.DEFAULT)
        }

    suspend fun updateConfiguration(config: Configuration) {
        dataStore.edit {
            it[Keys.CONFIG_JSON] = json.encodeToString(config)
        }
    }

    suspend fun loadDisabledPluginIds(): Set<String> {
        return dataStore.data.map { it[Keys.DISABLED_PLUGIN_IDS] ?: emptySet() }.first()
    }

    suspend fun saveDisabledPluginIds(ids: Set<String>) {
        dataStore.edit { it[Keys.DISABLED_PLUGIN_IDS] = ids }
    }

    suspend fun readConfigurationOnce(): Configuration = runCatching {
        val prefs = dataStore.data.first()
        prefs[Keys.CONFIG_JSON]
            ?.let { json.decodeFromString<Configuration>(it) }
            ?: Configuration.DEFAULT
    }.getOrElse { error ->
        logger.error("Failed to read settings, using default", error)
        Configuration.DEFAULT
    }
}

