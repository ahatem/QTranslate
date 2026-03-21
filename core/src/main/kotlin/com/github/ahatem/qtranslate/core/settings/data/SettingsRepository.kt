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
 * Persistence layer for application configuration and plugin state.
 *
 * All configuration reads and writes go through this class.
 * The reactive [configuration] flow is the primary way for the rest of the
 * application to observe configuration changes.
 *
 * ### Storage layout
 * - `datastore/app_settings.preferences_pb` — configuration JSON + disabled plugin IDs
 *
 * ### Error handling
 * Read failures fall back to [Configuration.DEFAULT] and are logged.
 * Write failures return a typed [SettingsError] via the `Result` type — callers
 * decide how to surface them to the user.
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

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /**
     * Reactive flow of the persisted configuration.
     *
     * Emits the current [Configuration] on first collection and again whenever
     * [updateConfiguration] succeeds. Falls back to [Configuration.DEFAULT] on
     * deserialization errors or I/O failures rather than terminating the flow.
     */
    val configuration: Flow<Configuration> = dataStore.data
        .map { preferences ->
            preferences[Keys.CONFIG_JSON]?.let { json ->
                try {
                    json.let { this.json.decodeFromString<Configuration>(it) }
                } catch (e: SerializationException) {
                    logger.error("Failed to deserialize configuration, using default", e)
                    Configuration.DEFAULT
                }
            } ?: Configuration.DEFAULT
        }
        .catch { e ->
            logger.error("Failed to read settings from DataStore, using default", e)
            emit(Configuration.DEFAULT)
        }

    /**
     * Loads the configuration once, synchronously with respect to the caller's coroutine.
     * Used during app startup before the reactive flow is established.
     *
     * @return The persisted configuration, or [Configuration.DEFAULT] if loading fails.
     */
    suspend fun loadInitialConfiguration(): Configuration =
        try {
            logger.debug("Loading initial configuration...")
            configuration.first().also {
                logger.info("Initial configuration loaded successfully")
            }
        } catch (e: Exception) {
            logger.error("Failed to load initial configuration, using default", e)
            Configuration.DEFAULT
        }

    /**
     * Persists [config] to storage.
     *
     * On success, the [configuration] flow will emit the new value.
     *
     * @return [Ok] on success, or an [Err] with a [SettingsError] describing the failure.
     */
    suspend fun updateConfiguration(config: Configuration): Result<Unit, SettingsError> =
        try {
            val jsonString = json.encodeToString(Configuration.serializer(), config)
            dataStore.edit { it[Keys.CONFIG_JSON] = jsonString }
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

    // -------------------------------------------------------------------------
    // Plugin enabled/disabled state
    // -------------------------------------------------------------------------

    /**
     * Returns the set of plugin IDs that the user has explicitly disabled.
     * Returns an empty set on failure.
     */
    suspend fun loadDisabledPluginIds(): Set<String> =
        try {
            dataStore.data.map { it[Keys.DISABLED_PLUGIN_IDS] ?: emptySet() }.first()
        } catch (e: Exception) {
            logger.error("Failed to load disabled plugin IDs", e)
            emptySet()
        }

    /**
     * Persists the complete set of disabled plugin IDs.
     * Replaces any previously stored set.
     */
    suspend fun saveDisabledPluginIds(ids: Set<String>) {
        try {
            dataStore.edit { it[Keys.DISABLED_PLUGIN_IDS] = ids }
            logger.debug("Saved ${ids.size} disabled plugin ID(s)")
        } catch (e: Exception) {
            logger.error("Failed to save disabled plugin IDs", e)
        }
    }
}

// -------------------------------------------------------------------------
// Error types
// -------------------------------------------------------------------------

/**
 * Typed errors that can occur during settings persistence operations.
 */
sealed interface SettingsError {
    val message: String

    /** The configuration could not be serialized to JSON. */
    data class SerializationError(override val message: String) : SettingsError

    /** A disk I/O error occurred while reading or writing the DataStore file. */
    data class IOError(override val message: String) : SettingsError

    /** An unexpected error occurred. */
    data class UnknownError(override val message: String) : SettingsError
}