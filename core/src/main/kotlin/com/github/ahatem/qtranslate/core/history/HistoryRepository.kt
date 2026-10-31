package com.github.ahatem.qtranslate.core.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.shared.AppConstants
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Persists translation history using DataStore.
 *
 * History is stored as a single JSON array and capped at
 * [AppConstants.MAX_HISTORY_ENTRIES] entries on every save to prevent
 * unbounded growth. Failures on read return an empty list; failures on
 * writes are returned to callers as typed errors.
 */
class HistoryRepository(
    appDataDirectory: File,
    private val logger: Logger,
    private val json: Json,
    dataStore: DataStore<Preferences>? = null,
    val storageScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val dataStore: DataStore<Preferences> = dataStore ?: PreferenceDataStoreFactory.create(
        scope = CoroutineScope(storageScope.coroutineContext + Dispatchers.IO),
        produceFile = { File(appDataDirectory, "datastore/${AppConstants.DATASTORE_HISTORY_FILE}") }
    )

    private object Keys {
        val HISTORY_JSON = stringPreferencesKey("translation_history_json")
    }

    suspend fun loadHistory(): List<HistorySnapshot> = try {
        logger.debug("Loading translation history...")
        val jsonString = dataStore.data.map { it[Keys.HISTORY_JSON] }.first()

        if (jsonString.isNullOrBlank()) {
            logger.info("No history found, starting fresh")
            emptyList()
        } else {
            val history = json.decodeFromString<List<HistorySnapshot>>(jsonString)
            logger.info("Loaded ${history.size} history entries")
            history
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SerializationException) {
        logger.error("Failed to deserialize history, starting fresh", e)
        emptyList()
    } catch (e: IOException) {
        logger.error("Failed to read history from disk, starting fresh", e)
        emptyList()
    } catch (e: Exception) {
        logger.error("Unexpected error loading history, starting fresh", e)
        emptyList()
    }

    suspend fun saveHistory(history: List<HistorySnapshot>): Result<Unit, HistoryError> {
        try {
            val truncated = history.takeLast(AppConstants.MAX_HISTORY_ENTRIES)
            logger.debug("Saving ${truncated.size} history entries...")

            // encodeToString<List<T>> infers the serializer automatically —
            // no need to pass ListSerializer(HistorySnapshot.serializer()) explicitly.
            dataStore.edit { it[Keys.HISTORY_JSON] = json.encodeToString(truncated) }

            logger.info("History saved successfully")
            return Ok(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            logger.error("Failed to serialize history", e)
            return Err(HistoryError.SerializationError(e.message ?: "Serialization failed"))
        } catch (e: IOException) {
            logger.error("Failed to write history to disk", e)
            return Err(HistoryError.IOError(e.message ?: "Disk write failed"))
        } catch (e: Exception) {
            logger.error("Unexpected error saving history", e)
            return Err(HistoryError.UnknownError(e.message ?: "Unknown error"))
        }
    }

    suspend fun clearHistory(): Result<Unit, HistoryError> {
        try {
            logger.info("Clearing translation history...")
            dataStore.edit { it.clear() }
            logger.info("History cleared successfully")
            return Ok(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.error("Failed to clear history", e)
            return Err(HistoryError.IOError(e.message ?: "Disk write failed"))
        } catch (e: Exception) {
            logger.error("Unexpected error clearing history", e)
            return Err(HistoryError.UnknownError(e.message ?: "Unknown error"))
        }
    }
}
/** Failures while persisting translation history. */
sealed interface HistoryError {
    val message: String
    data class SerializationError(override val message: String) : HistoryError
    data class IOError(override val message: String) : HistoryError
    data class UnknownError(override val message: String) : HistoryError
}
