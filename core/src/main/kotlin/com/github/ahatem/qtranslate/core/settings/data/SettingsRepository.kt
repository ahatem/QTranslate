package com.github.ahatem.qtranslate.core.settings.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Repository for persistent settings storage using DataStore.
 *
 * This is the single source of truth for configuration persistence.
 * All configuration reads/writes go through this repository.
 */
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
        produceFile = {
            File(appDataDirectory, "datastore/${AppConstants.DATASTORE_FILE}")
        }
    )

    /**
     * Reactive flow of configuration changes.
     *
     * This flow emits whenever the persisted configuration changes.
     * Components should observe this flow to react to configuration updates.
     */
    val configuration: Flow<Configuration> = dataStore.data
        .map { preferences ->
            preferences[Keys.CONFIG_JSON]?.let { jsonString ->
                try {
                    json.decodeFromString<Configuration>(jsonString)
                } catch (e: SerializationException) {
                    logger.error("Failed to deserialize configuration, using default", e)
                    Configuration.DEFAULT
                }
            } ?: Configuration.DEFAULT
        }
        .catch { error ->
            logger.error("Failed to read settings from DataStore, using default", error)
            emit(Configuration.DEFAULT)
        }

    /**
     * Persists a configuration to storage.
     *
     * This is the only way to save configuration changes.
     * After successful save, the [configuration] flow will emit the new config.
     *
     * @param config The configuration to persist
     * @return Result indicating success or the error that occurred
     */
    suspend fun updateConfiguration(config: Configuration): Result<Unit, SettingsError> {
        return try {
            val jsonString = json.encodeToString(Configuration.serializer(), config)

            dataStore.edit { preferences ->
                preferences[Keys.CONFIG_JSON] = jsonString
            }

            logger.info("Configuration saved successfully")
            Ok(Unit)

        } catch (e: SerializationException) {
            logger.error("Failed to serialize configuration", e)
            Err(SettingsError.SerializationError(e.message ?: "Serialization failed"))

        } catch (e: IOException) {
            logger.error("Failed to write configuration to disk", e)
            Err(SettingsError.IOError(e.message ?: "Disk write failed"))

        } catch (e: Exception) {
            logger.error("Unexpected error saving configuration", e)
            Err(SettingsError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Loads the initial configuration synchronously.
     *
     * This is used during app startup before the reactive flow is set up.
     * It reads from the DataStore flow but doesn't establish a collector.
     *
     * @return The persisted configuration, or DEFAULT if loading fails
     */
    suspend fun loadInitialConfiguration(): Configuration {
        return try {
            logger.debug("Loading initial configuration...")
            val config = configuration.first()
            logger.info("Initial configuration loaded successfully")
            config
        } catch (e: Exception) {
            logger.error("Failed to load initial configuration, using default", e)
            Configuration.DEFAULT
        }
    }

    /**
     * Loads the set of disabled plugin IDs.
     *
     * @return Set of plugin IDs that are disabled
     */
    suspend fun loadDisabledPluginIds(): Set<String> {
        return try {
            dataStore.data
                .map { it[Keys.DISABLED_PLUGIN_IDS] ?: emptySet() }
                .first()
        } catch (e: Exception) {
            logger.error("Failed to load disabled plugin IDs", e)
            emptySet()
        }
    }

    /**
     * Persists the set of disabled plugin IDs.
     *
     * @param ids Set of plugin IDs to mark as disabled
     */
    suspend fun saveDisabledPluginIds(ids: Set<String>) {
        try {
            dataStore.edit { preferences ->
                preferences[Keys.DISABLED_PLUGIN_IDS] = ids
            }
            logger.debug("Saved ${ids.size} disabled plugin IDs")
        } catch (e: Exception) {
            logger.error("Failed to save disabled plugin IDs", e)
        }
    }
}

/**
 * Errors that can occur during settings operations.
 */
sealed interface SettingsError {
    val message: String

    data class SerializationError(override val message: String) : SettingsError
    data class IOError(override val message: String) : SettingsError
    data class UnknownError(override val message: String) : SettingsError
}