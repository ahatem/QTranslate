package com.github.ahatem.qtranslate.core.settings.mvi

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsError
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SettingsSaveReceiptTest {
    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    private class GateStore : DataStore<Preferences> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fail = false
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            entered.complete(Unit)
            release.await()
            if (fail) throw IOException("failure")
            return transform(state.value).also { state.value = it }
        }
    }
    @Test fun `save receipt belongs to request and preserves later edits`() = runTest {
        val data = GateStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SettingsStore(SettingsRepository(File("."), Json, logger, data), logger, scope, Configuration.DEFAULT)
            store.dispatch(SettingsIntent.TransformDraft { it.copy(uiScale = 125) })
            store.dispatch(SettingsIntent.DeletePreset("missing")) // Unrelated message must not complete this save.
            val pending = async { store.saveDraftAndAwait() }
            data.entered.await()
            assertFalse(pending.isCompleted)
            store.dispatch(SettingsIntent.TransformDraft { it.copy(uiScale = 150) })
            data.release.complete(Unit)
            assertEquals(Ok(Unit), pending.await())
            assertEquals(125, store.state.value.originalConfiguration.uiScale)
            assertEquals(150, store.state.value.workingConfiguration.uiScale)
            assertTrue(store.state.value.isDirty)
        } finally { scope.cancel() }
    }
    @Test fun `accepted save survives cancellation of its waiter`() = runTest {
        val data = GateStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SettingsStore(SettingsRepository(File("."), Json, logger, data), logger, scope, Configuration.DEFAULT)
            store.dispatch(SettingsIntent.TransformDraft { it.copy(uiScale = 125) })
            val pending = launch { store.saveDraftAndAwait() }
            data.entered.await()
            pending.cancelAndJoin()
            data.release.complete(Unit)
            withTimeout(10_000) { store.state.first { it.originalConfiguration.uiScale == 125 && !it.isSaving } }
            assertFalse(store.state.value.isDirty)
        } finally { scope.cancel() }
    }
    @Test fun `failure is returned and no-op completes without writing`() = runTest {
        val data = GateStore().apply { fail = true; release.complete(Unit) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SettingsStore(SettingsRepository(File("."), Json, logger, data), logger, scope, Configuration.DEFAULT)
            assertEquals(Ok(Unit), store.saveDraftAndAwait())
            assertFalse(data.entered.isCompleted)
            store.dispatch(SettingsIntent.TransformDraft { it.copy(uiScale = 125) })
            assertEquals(Err(SettingsError.IOError("failure")), store.saveDraftAndAwait())
            assertTrue(store.state.value.isDirty)
        } finally { scope.cancel() }
    }
    @Test fun `concurrent transforms apply to current draft`() = runBlocking {
        val data = GateStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SettingsStore(SettingsRepository(File("."), Json, logger, data), logger, scope, Configuration.DEFAULT)
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val jobs = (1..2).map { async(Dispatchers.Default) {
                var first = true
                store.dispatch(SettingsIntent.TransformDraft { config ->
                    if (first) { first = false; entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    config.copy(uiScale = config.uiScale + 1)
                })
            } }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            release.countDown()
            jobs.awaitAll()
            assertEquals(Configuration.DEFAULT.uiScale + 2, store.state.value.workingConfiguration.uiScale)
        } finally { scope.cancel() }
    }
    @Test fun `shutdown drains queued accepted save before returning`() = runTest {
        val data = GateStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SettingsStore(SettingsRepository(File("."), Json, logger, data), logger, scope, Configuration.DEFAULT)
            store.dispatch(SettingsIntent.TransformDraft { it.copy(uiScale = 125) })
            val first = store.submitDraftSave()
            data.entered.await()
            store.dispatch(SettingsIntent.TransformDraft { it.copy(uiScale = 150) })
            val second = store.submitDraftSave()
            val drain = async(Dispatchers.Default) { store.drainAcceptedSaves() }
            runCurrent()
            assertFalse(drain.isCompleted)
            data.release.complete(Unit)
            assertEquals(Ok(Unit), first.await())
            assertEquals(Ok(Unit), second.await())
            assertTrue(drain.await())
            assertEquals(150, store.state.value.originalConfiguration.uiScale)
            assertFailsWith<IllegalStateException> { store.submitDraftSave() }
        } finally { scope.cancel() }
    }

    @Test fun `accepted completion survives cancelled waiter and is drained`() = runTest {
        val data = GateStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SettingsStore(SettingsRepository(File("."), Json, logger, data), logger, scope, Configuration.DEFAULT)
            store.dispatch(SettingsIntent.TransformDraft { it.copy(uiScale = 125) })
            val completed = CompletableDeferred<Unit>()
            val receipt = store.submitDraftSave { completed.complete(Unit) }
            val waiter = launch { receipt.await() }
            data.entered.await()
            waiter.cancelAndJoin()
            val drain = async(Dispatchers.Default) { store.drainAcceptedSaves() }
            data.release.complete(Unit)
            assertTrue(drain.await())
            assertTrue(completed.isCompleted)
        } finally { scope.cancel() }
    }

    @Test fun `failed configuration does not run completion`() = runTest {
        val data = GateStore().apply { fail = true; release.complete(Unit) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SettingsStore(SettingsRepository(File("."), Json, logger, data), logger, scope, Configuration.DEFAULT)
            store.dispatch(SettingsIntent.TransformDraft { it.copy(uiScale = 125) })
            var completed = false
            assertEquals(Err(SettingsError.IOError("failure")), store.submitDraftSave { completed = true }.await())
            assertFalse(completed)
        } finally { scope.cancel() }
    }

    @Test fun `queued save keeps accepted snapshot even if draft is cancelled`() = runTest {
        val data = GateStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SettingsStore(SettingsRepository(File("."), Json, logger, data), logger, scope, Configuration.DEFAULT)
            store.dispatch(SettingsIntent.TransformDraft { it.copy(uiScale = 125) })
            val first = store.submitDraftSave()
            data.entered.await()
            store.dispatch(SettingsIntent.TransformDraft { it.copy(uiScale = 150) })
            val second = store.submitDraftSave()
            store.dispatch(SettingsIntent.CancelChanges)
            data.release.complete(Unit)
            assertEquals(Ok(Unit), first.await())
            assertEquals(Ok(Unit), second.await())
            assertEquals(150, store.state.value.originalConfiguration.uiScale)
        } finally { scope.cancel() }
    }

}
