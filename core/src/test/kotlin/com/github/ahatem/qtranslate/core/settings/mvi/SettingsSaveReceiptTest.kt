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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
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
            // The save is accepted in the store's own scope, so its own completion hook is the only
            // signal that the write landed. Waiting for state instead races that write, and a
            // `withTimeout` here would be virtual time: the store runs on a real dispatcher, so the
            // scheduler sees no work of its own and fires the timeout before the write finishes.
            val committed = CompletableDeferred<Unit>()
            val pending = launch { store.submitDraftSave { committed.complete(Unit) }.await() }
            data.entered.await()
            pending.cancelAndJoin()
            data.release.complete(Unit)
            committed.await()
            assertEquals(125, store.state.value.originalConfiguration.uiScale)
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

    @Test fun `a late echo of an earlier write does not revert a newer commit`() = runTest {
        // The repository delivers its flow on its own dispatcher, so the echo of write #1 can be
        // collected after write #2 has already been committed. Applying it then would report the
        // older configuration as the saved one and recompute isDirty against it.
        val data = ControlledStore()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val store = SettingsStore(SettingsRepository(File("."), Json, logger, data), logger, scope, Configuration.DEFAULT)
            store.dispatch(SettingsIntent.TransformDraft { it.copy(uiScale = 125) })
            assertEquals(Ok(Unit), store.submitDraftSave().await())
            store.dispatch(SettingsIntent.TransformDraft { it.copy(uiScale = 150) })
            assertEquals(Ok(Unit), store.submitDraftSave().await())
            advanceUntilIdle()
            assertEquals(150, store.state.value.originalConfiguration.uiScale)

            // Only now does the first write reach the observer.
            data.publish(0)
            advanceUntilIdle()

            assertEquals(
                150,
                store.state.value.originalConfiguration.uiScale,
                "a late echo of the first write must not revert the second"
            )
            assertEquals(150, store.state.value.workingConfiguration.uiScale)
            assertFalse(store.state.value.isDirty)
        } finally { scope.cancel() }
    }

    @Test fun `an external repository change still syncs an idle store`() = runTest {
        // Suppressing the store's own echoes must not stop it from adopting a change it did not
        // make, which is the reason the observer exists.
        val data = ControlledStore()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val store = SettingsStore(SettingsRepository(File("."), Json, logger, data), logger, scope, Configuration.DEFAULT)
            // Written by something other than this store, then delivered through the flow.
            SettingsRepository(File("."), Json, logger, data)
                .updateConfiguration(Configuration.DEFAULT.copy(uiScale = 130))
            data.publish(0)
            advanceUntilIdle()

            assertEquals(130, store.state.value.originalConfiguration.uiScale)
            assertEquals(130, store.state.value.workingConfiguration.uiScale)
            assertFalse(store.state.value.isDirty)
        } finally { scope.cancel() }
    }

    /**
     * A store whose flow is delivered on demand, so a test can choose the order in which the
     * repository's emissions reach the observer.
     */
    private class ControlledStore : DataStore<Preferences> {
        private val published = MutableStateFlow<Preferences?>(null)
        private val current = MutableStateFlow(emptyPreferences())
        private val written = mutableListOf<Preferences>()

        override val data: Flow<Preferences> = published.filterNotNull()

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(current.value)
            current.value = updated
            written += updated
            // Deliberately not published: the test decides when the flow emits.
            return updated
        }

        /** Delivers the echo of the write at [index], counting from the first write. */
        fun publish(index: Int) {
            published.value = written[index]
        }
    }

}
