package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)
class SettingsStoreDraftSemanticsTest {
    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    private val directories = mutableListOf<java.io.File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun cleanUp() {
        scopes.forEach { it.coroutineContext.cancel() }
        directories.forEach { it.deleteRecursively() }
    }

    @Test
    fun `preset edits cancel independently and apply persists`() = runTest {
        val second = ServicePreset.createDefault("Second")
        val initial = Configuration.DEFAULT.copy(servicePresets = Configuration.DEFAULT.servicePresets + second)
        val store = store(initial)

        store.dispatch(SettingsIntent.CreatePreset("Draft"))
        store.dispatch(SettingsIntent.CancelChanges)
        assertEquals(initial, store.state.value.originalConfiguration)

        store.dispatch(SettingsIntent.RenamePreset(second.id, "Renamed"))
        store.dispatch(SettingsIntent.CancelChanges)
        assertEquals("Second", store.state.value.originalConfiguration.servicePresets.last().name)

        store.dispatch(SettingsIntent.SetActivePreset(second.id))
        store.dispatch(SettingsIntent.CancelChanges)
        assertEquals(initial.activeServicePresetId, store.state.value.originalConfiguration.activeServicePresetId)

        store.dispatch(SettingsIntent.DeletePreset(second.id))
        store.dispatch(SettingsIntent.CancelChanges)
        assertTrue(store.state.value.originalConfiguration.servicePresets.any { it.id == second.id })

        store.dispatch(SettingsIntent.CreatePreset("Applied"))
        awaitSave(store)
        assertTrue(store.state.value.originalConfiguration.servicePresets.any { it.name == "Applied" })
    }

    @Test
    fun `service assignment cancel does not persist and apply does`() = runTest {
        val store = store(Configuration.DEFAULT)
        val original = Configuration.DEFAULT.getActivePreset()!!.selectedServices[ServiceRole.TRANSLATOR]
        val replacement = "replacement-translator"

        store.dispatch(SettingsIntent.UpdateServiceInActivePreset(ServiceRole.TRANSLATOR, replacement))
        store.dispatch(SettingsIntent.CancelChanges)
        assertEquals(original, store.state.value.originalConfiguration.getActivePreset()!!.selectedServices[ServiceRole.TRANSLATOR])

        store.dispatch(SettingsIntent.UpdateServiceInActivePreset(ServiceRole.TRANSLATOR, replacement))
        awaitSave(store)
        assertEquals(replacement, store.state.value.originalConfiguration.getActivePreset()!!.selectedServices[ServiceRole.TRANSLATOR])
    }

    @Test
    fun `translation rules cancel and apply preserve meaningful changes`() = runTest {
        val rule = TranslationRule("en", "fr")
        val store = store(Configuration.DEFAULT.copy(translationRules = listOf(rule)))

        store.dispatch(SettingsIntent.RemoveTranslationRule(rule))
        store.dispatch(SettingsIntent.CancelChanges)
        assertEquals(listOf(rule), store.state.value.originalConfiguration.translationRules)

        store.dispatch(SettingsIntent.RemoveTranslationRule(rule))
        awaitSave(store)
        assertTrue(store.state.value.originalConfiguration.translationRules.isEmpty())

        store.dispatch(SettingsIntent.AddTranslationRule(rule))
        store.dispatch(SettingsIntent.CancelChanges)
        assertTrue(store.state.value.originalConfiguration.translationRules.isEmpty())

        store.dispatch(SettingsIntent.AddTranslationRule(rule))
        awaitSave(store)
        assertEquals(listOf(rule), store.state.value.originalConfiguration.translationRules)
    }

    @Test
    fun `reset cancel keeps old configuration and apply persists defaults`() = runTest {
        val initial = Configuration.DEFAULT.copy(interfaceLanguage = "ar")
        val store = store(initial)

        store.dispatch(SettingsIntent.ResetToDefaults)
        store.dispatch(SettingsIntent.CancelChanges)
        assertEquals(initial, store.state.value.originalConfiguration)

        store.dispatch(SettingsIntent.ResetToDefaults)
        awaitSave(store)
        assertEquals(Configuration.DEFAULT, store.state.value.originalConfiguration)
    }

    @Test
    fun `rapid scoped actions preserve both updates`() = runTest {
        val store = store(Configuration.DEFAULT)
        val saves = async(start = CoroutineStart.UNDISPATCHED) {
            repeat(2) { awaitSuccessEvent(store) }
        }

        store.dispatch(SettingsIntent.ToggleSetting { it.copy(isGlobalHotkeysEnabled = false) })
        store.dispatch(SettingsIntent.ToggleSetting { it.copy(isSpellCheckingEnabled = false) })
        saves.await()

        assertFalse(store.state.value.originalConfiguration.isGlobalHotkeysEnabled)
        assertFalse(store.state.value.originalConfiguration.isSpellCheckingEnabled)
    }

    @Test
    fun `dirty draft survives scoped action and apply while cancel keeps only scoped action`() = runTest {
        val initial = Configuration.DEFAULT.copy(interfaceLanguage = "en")
        val store = store(initial)

        store.dispatch(SettingsIntent.UpdateDraft(initial.copy(interfaceLanguage = "ar")))
        store.dispatch(SettingsIntent.ToggleSetting { it.copy(isGlobalHotkeysEnabled = false) })
        awaitSuccessEvent(store)
        assertEquals("ar", store.state.value.workingConfiguration.interfaceLanguage)
        assertFalse(store.state.value.workingConfiguration.isGlobalHotkeysEnabled)
        awaitSave(store)
        assertEquals("ar", store.state.value.originalConfiguration.interfaceLanguage)
        assertFalse(store.state.value.originalConfiguration.isGlobalHotkeysEnabled)

        val cancelStore = store(initial)
        cancelStore.dispatch(SettingsIntent.UpdateDraft(initial.copy(interfaceLanguage = "ar")))
        cancelStore.dispatch(SettingsIntent.ToggleSetting { it.copy(isGlobalHotkeysEnabled = false) })
        awaitSuccessEvent(cancelStore)
        cancelStore.dispatch(SettingsIntent.CancelChanges)
        assertEquals("en", cancelStore.state.value.workingConfiguration.interfaceLanguage)
        assertFalse(cancelStore.state.value.originalConfiguration.isGlobalHotkeysEnabled)
    }

    private suspend fun awaitSave(store: SettingsStore) {
        val event = coroutineScope {
            val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                store.events.filterIsInstance<SettingsEvent.ShowMessage>().first()
            }
            store.dispatch(SettingsIntent.SaveChanges)
            deferred.await()
        }
        assertEquals(NotificationType.SUCCESS, event.type)
    }

    private suspend fun awaitSuccessEvent(store: SettingsStore) =
        store.events.filterIsInstance<SettingsEvent.ShowMessage>().first()

    private suspend fun store(config: Configuration): SettingsStore {
        val directory = Files.createTempDirectory("qtranslate-settings-draft").toFile()
        directories += directory
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        return SettingsStore(repository, logger, scope, config)
    }
}
