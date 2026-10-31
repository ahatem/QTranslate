package com.github.ahatem.qtranslate.core.plugin.storage

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.history.HistoryRepository
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.michaelbull.result.Ok
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class DataStoreLifetimeTest {
    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    @Test fun `same files reopen after owned scope completes`() = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-store-lifetime").toFile()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings = SettingsRepository(directory, Json, logger, storageScope = scope)
            val history = HistoryRepository(directory, logger, Json, storageScope = scope)
            val keys = PluginKeyValueStore(directory, scope)
            val fingerprints = PluginFingerprintRepository(directory, Json, scope)
            assertEquals(Ok(Unit), settings.updateConfiguration(Configuration.DEFAULT.copy(uiScale = 125)))
            assertEquals(Ok(Unit), history.saveHistory(emptyList()))
            keys.storeValue("test", "key", "value")
            fingerprints.storeFingerprints(listOf(PluginFingerprint("test", "hash")))
            scope.coroutineContext[Job]!!.cancelAndJoin()

            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val settings2 = SettingsRepository(directory, Json, logger, storageScope = scope)
            val history2 = HistoryRepository(directory, logger, Json, storageScope = scope)
            val keys2 = PluginKeyValueStore(directory, scope)
            val fingerprints2 = PluginFingerprintRepository(directory, Json, scope)
            assertEquals(125, settings2.loadInitialConfiguration().uiScale)
            assertEquals(emptyList(), history2.loadHistory())
            assertEquals("value", keys2.getValue("test", "key"))
            assertEquals(mapOf("test" to "hash"), fingerprints2.loadFingerprints())
            keys2.deleteAllData("test")
            keys2.storeValue("test", "key", "next")
            assertEquals("next", keys2.getValue("test", "key"))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
