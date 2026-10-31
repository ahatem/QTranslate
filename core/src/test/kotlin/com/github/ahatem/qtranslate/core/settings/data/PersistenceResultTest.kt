package com.github.ahatem.qtranslate.core.settings.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.history.HistoryError
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.michaelbull.result.Err
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class PersistenceResultTest {
    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    private val json = Json { ignoreUnknownKeys = true }

    private class TestStore(private val failure: Exception? = null) : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> get() = failure?.let { kotlinx.coroutines.flow.flow { throw it } } ?: state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            failure?.let { throw it }
            transform(state.value).also { state.value = it }
        }
    }

    @Test fun `concurrent disabled plugin updates preserve every id`() = runBlocking {
        val repo = SettingsRepository(File("."), json, logger, TestStore())
        (1..100).map { id -> async(Dispatchers.Default) { repo.updateDisabledPluginIds { it + id.toString() } } }.awaitAll()
        assertEquals((1..100).map(Int::toString).toSet(), repo.loadDisabledPluginIds())
    }

    @Test fun `write failures are typed`() = runBlocking {
        val store = TestStore(IOException("disk unavailable"))
        val settings = SettingsRepository(File("."), json, logger, store)
        val history = HistoryRepository(File("."), logger, json, store)
        assertEquals(Err(SettingsError.IOError("disk unavailable")), settings.updateDisabledPluginIds { it + "plugin" })
        assertEquals(Err(HistoryError.IOError("disk unavailable")), history.saveHistory(emptyList()))
        assertEquals(Err(HistoryError.IOError("disk unavailable")), history.clearHistory())
    }

    @Test fun `cancelling suspended configuration read does not recover`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = kotlinx.coroutines.flow.flow {
                entered.complete(Unit)
                awaitCancellation()
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("unused")
        }
        val repo = SettingsRepository(File("."), json, logger, store)
        var completed = false
        val job = launch { repo.loadInitialConfiguration(); completed = true }
        entered.await()
        job.cancelAndJoin()
        assertFalse(completed)
        assertNull(repo.lastRecovery)
    }

    @Test fun `cancellation propagates through reads and writes`() = runBlocking {
        val store = TestStore(CancellationException("cancelled"))
        val settings = SettingsRepository(File("."), json, logger, store)
        val history = HistoryRepository(File("."), logger, json, store)
        assertFailsWith<CancellationException> { settings.loadDisabledPluginIds() }
        assertFailsWith<CancellationException> { settings.updateConfiguration(Configuration.DEFAULT) }
        assertFailsWith<CancellationException> { settings.updateDisabledPluginIds { it } }
        assertFailsWith<CancellationException> { history.loadHistory() }
        assertFailsWith<CancellationException> { history.saveHistory(emptyList()) }
        assertFailsWith<CancellationException> { history.clearHistory() }
        Unit
    }
}
