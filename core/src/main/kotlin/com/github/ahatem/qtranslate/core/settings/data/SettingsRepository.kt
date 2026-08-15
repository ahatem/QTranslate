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
/**
 * What happened when a stored configuration could not be read.
 *
 * @param backupFile where the unreadable configuration was copied, so nothing is lost.
 * @param reason the failure, for showing to the user.
 */
data class ConfigRecovery(val backupFile: File?, val reason: String)

class SettingsRepository(
    private val appDataDirectory: File,
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
            preferences[Keys.CONFIG_JSON]?.let { stored ->
                try {
                    ConfigMigrator.migrate(this.json.decodeFromString<Configuration>(stored), logger)
                } catch (e: Exception) {
                    // Any failure, not only SerializationException: a migration step throwing
                    // something else used to escape this catch and be swallowed by the flow's
                    // handler below, which reached the same silent default by a longer route.
                    recoverFrom(stored, e)
                }
            } ?: Configuration.DEFAULT   // No stored config at all: a fresh install, not a failure.
        }
        .catch { e ->
            // The store itself is unreadable — a truncated or corrupted file, rather than JSON
            // this version cannot parse. There is no configuration string to keep, so the raw
            // file is copied instead: it is still the user's data, and a corrupt protobuf is
            // often still legible enough to recover a service choice or an API endpoint by hand.
            logger.error("Failed to read settings from DataStore, using default", e)
            val backup = backUpRawStore()
            lastRecovery = ConfigRecovery(
                backupFile = backup,
                reason = buildString {
                    append("Your settings file could not be read (${e.javaClass.simpleName}) ")
                    append("and QTranslate has started with defaults. ")
                    append(
                        backup?.let { "A copy of the unreadable file was saved as ${it.name}." }
                            ?: "The file could not be copied aside."
                    )
                }
            )
            emit(Configuration.DEFAULT)
        }

    /** Timestamped name for a recovery copy, so repeated failures do not overwrite each other. */
    private fun backupName(extension: String): String {
        val stamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return "config-backup-$stamp.$extension"
    }

    /** Copies the datastore file itself aside, for when it cannot be parsed at all. */
    private fun backUpRawStore(): File? = runCatching {
        val directory = File(appDataDirectory, "datastore").apply { mkdirs() }
        val source = File(directory, AppConstants.DATASTORE_FILE)
        if (!source.exists()) return@runCatching null
        File(directory, backupName("preferences_pb"))
            .also { source.copyTo(it, overwrite = true) }
    }.onFailure {
        logger.error("Could not copy the unreadable settings file aside", it)
    }.getOrNull()

    /**
     * Set when a stored configuration could not be read, and left null otherwise.
     *
     * Read once the UI is up so the failure can be shown. Falling back to defaults silently is
     * the worst available behaviour: every preset, service choice, hotkey and window position
     * appears to have been forgotten, the app looks freshly installed, and nothing says why —
     * so the natural next step is to reconfigure everything, which then overwrites the file that
     * still held the original.
     */
    @Volatile
    var lastRecovery: ConfigRecovery? = null
        private set

    /**
     * Copies an unreadable configuration aside, records why, and returns defaults.
     *
     * The copy is the part that matters. Whatever is wrong with the file, its contents are the
     * user's — and the app is about to start writing defaults over the same key.
     */
    private fun recoverFrom(storedJson: String, error: Throwable): Configuration {
        logger.error("Stored configuration could not be read; starting with defaults", error)

        val backup = runCatching {
            val directory = File(appDataDirectory, "datastore").apply { mkdirs() }
            File(directory, backupName("json")).apply { writeText(storedJson) }
        }.onFailure {
            logger.error("Could not write the configuration backup", it)
        }.getOrNull()

        backup?.let { logger.info("Previous configuration saved to ${it.absolutePath}") }

        lastRecovery = ConfigRecovery(
            backupFile = backup,
            reason = buildString {
                append("Your settings could not be read (${error.javaClass.simpleName}) ")
                append("and QTranslate has started with defaults. ")
                append(
                    backup?.let { "A copy of the previous settings was saved to ${it.name}." }
                        ?: "The previous settings could not be backed up."
                )
            }
        )
        return Configuration.DEFAULT
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