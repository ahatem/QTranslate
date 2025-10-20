package com.github.ahatem.qtranslate.core.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.File

class HistoryRepository(
    appDataDirectory: File,
    private val logger: Logger,
    private val json: Json
) {
    private object Keys {
        val HISTORY_JSON = stringPreferencesKey("translation_history_json")
    }

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(appDataDirectory, "datastore/history.preferences_pb") }
    )

    suspend fun loadHistory(): List<HistorySnapshot> =
        runCatching {
            val jsonString = dataStore.data.map { it[Keys.HISTORY_JSON] }.first()
            if (jsonString.isNullOrBlank()) emptyList()
            else json.decodeFromString<List<HistorySnapshot>>(jsonString)
        }.getOrElse {
            logger.error("Failed to load history, starting fresh.", it)
            emptyList()
        }

    suspend fun saveHistory(history: List<HistorySnapshot>) =
        runCatching {
            val jsonString = json.encodeToString(history)
            dataStore.edit { it[Keys.HISTORY_JSON] = jsonString }
        }.onFailure {
            logger.error("Failed to save history.", it)
        }


    suspend fun clearHistory() =
        runCatching {
            dataStore.edit { it.clear() }
        }.onFailure {
            logger.error("Failed to clear history.", it)
        }
}
