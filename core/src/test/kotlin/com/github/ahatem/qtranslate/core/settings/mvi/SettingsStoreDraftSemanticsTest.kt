package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class SettingsStoreDraftSemanticsTest {
    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    private val directories = mutableListOf<java.io.File>()

    @AfterTest
    fun cleanUp() = directories.forEach { it.deleteRecursively() }

    @Test
    fun `preset and translation rule edits are draft only until apply`() = runTest {
        val second = ServicePreset.createDefault("Second")
        val initial = Configuration.DEFAULT.copy(servicePresets = Configuration.DEFAULT.servicePresets + second)
        val repository = repository()
        repository.updateConfiguration(initial)
        val store = SettingsStore(repository, logger, CoroutineScope(StandardTestDispatcher(testScheduler)), initial)
        advanceUntilIdle()

        store.dispatch(SettingsIntent.CreatePreset("Draft"))
        store.dispatch(SettingsIntent.RenamePreset(second.id, "Renamed"))
        store.dispatch(SettingsIntent.SetActivePreset(second.id))
        val rule = TranslationRule("en", "fr")
        store.dispatch(SettingsIntent.AddTranslationRule(rule))
        advanceUntilIdle()
        store.dispatch(SettingsIntent.CancelChanges)
        advanceUntilIdle()

        assertEquals(initial, store.state.value.originalConfiguration)

        store.dispatch(SettingsIntent.DeletePreset(second.id))
        store.dispatch(SettingsIntent.RemoveTranslationRule(rule))
        store.dispatch(SettingsIntent.SaveChanges)
        advanceUntilIdle()
        awaitPersistence()
        assertFalse(store.state.value.originalConfiguration.servicePresets.any { it.id == second.id })
        val applied = store.state.value.originalConfiguration
        assertFalse(applied.servicePresets.any { it.id == second.id })
        assertFalse(rule in applied.translationRules)
    }

    @Test
    fun `reset and service assignment cancel without persistence`() = runTest {
        val initial = Configuration.DEFAULT.copy(
            network = Configuration.DEFAULT.network.copy(proxyUrl = "http://old")
        )
        val repository = repository()
        repository.updateConfiguration(initial)
        val store = SettingsStore(repository, logger, CoroutineScope(StandardTestDispatcher(testScheduler)), initial)
        advanceUntilIdle()

        store.dispatch(SettingsIntent.UpdateServiceInActivePreset(ServiceRole.TRANSLATOR, null))
        store.dispatch(SettingsIntent.ResetToDefaults)
        store.dispatch(SettingsIntent.CancelChanges)
        advanceUntilIdle()
        assertEquals(initial, store.state.value.originalConfiguration)

        store.dispatch(SettingsIntent.ResetToDefaults)
        store.dispatch(SettingsIntent.SaveChanges)
        advanceUntilIdle()
        awaitPersistence()
        assertEquals(Configuration.DEFAULT, store.state.value.originalConfiguration)
    }

    @Test
    fun `external quick action does not commit dirty settings draft`() = runTest {
        val initial = Configuration.DEFAULT.copy(interfaceLanguage = "en")
        val repository = repository()
        repository.updateConfiguration(initial)
        val store = SettingsStore(repository, logger, CoroutineScope(StandardTestDispatcher(testScheduler)), initial)
        advanceUntilIdle()

        store.dispatch(SettingsIntent.UpdateDraft(initial.copy(interfaceLanguage = "ar")))
        store.dispatch(SettingsIntent.ToggleSetting { it.copy(isGlobalHotkeysEnabled = false) })
        advanceUntilIdle()
        awaitPersistence()
        assertFalse(store.state.value.originalConfiguration.isGlobalHotkeysEnabled)

        assertEquals("en", store.state.value.originalConfiguration.interfaceLanguage)
        assertFalse(store.state.value.originalConfiguration.isGlobalHotkeysEnabled)
        assertEquals("ar", store.state.value.workingConfiguration.interfaceLanguage)
        assertTrue(store.state.value.isDirty)
    }

    private fun repository(): SettingsRepository {
        val directory = Files.createTempDirectory("qtranslate-settings-draft").toFile()
        directories += directory
        return SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
    }

    private suspend fun awaitPersistence() = withContext(Dispatchers.IO) {
        Thread.sleep(150)
    }

}
