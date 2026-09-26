package com.github.ahatem.qtranslate.core.settings.mvi

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.data.TranslationRule
import com.github.ahatem.qtranslate.core.settings.data.TranslatorMove
import com.github.ahatem.qtranslate.core.settings.data.translatorSetIds
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
    fun `translator set edits cancel and apply without rewriting ids`() = runTest {
        val store = store(Configuration.DEFAULT)
        val original = Configuration.DEFAULT.getActivePreset()!!

        store.dispatch(SettingsIntent.AddTranslatorToActivePreset("missing"))
        store.dispatch(SettingsIntent.AddTranslatorToActivePreset("second"))
        assertEquals(
            listOf("missing", "second"),
            store.state.value.workingConfiguration.getActivePreset()!!.comparisonTranslatorIds
        )
        assertEquals(original, store.state.value.originalConfiguration.getActivePreset())
        store.dispatch(SettingsIntent.CancelChanges)
        assertEquals(original, store.state.value.workingConfiguration.getActivePreset())
        assertTrue(store.state.value.originalConfiguration.getActivePreset()!!.comparisonTranslatorIds.isEmpty())

        store.dispatch(SettingsIntent.AddTranslatorToActivePreset("missing"))
        store.dispatch(SettingsIntent.AddTranslatorToActivePreset("second"))
        awaitSave(store)
        assertEquals(
            listOf("missing", "second"),
            store.state.value.originalConfiguration.getActivePreset()!!.comparisonTranslatorIds
        )
    }

    @Test
    fun `remove promote and reorder edit only the working draft until applied`() = runTest {
        val seeded = translatorConfig("a", "b", "c", "d")
        val store = store(seeded)
        fun working() = store.state.value.workingConfiguration.getActivePreset()!!

        store.dispatch(SettingsIntent.MoveTranslatorInActivePreset("d", TranslatorMove.UP))
        assertEquals(listOf("b", "d", "c"), working().comparisonTranslatorIds)
        store.dispatch(SettingsIntent.PromoteTranslatorToPrimary("d"))
        assertEquals("d", working().selectedServices[ServiceRole.TRANSLATOR])
        assertEquals(listOf("b", "a", "c"), working().comparisonTranslatorIds)
        store.dispatch(SettingsIntent.RemoveTranslatorFromActivePreset("d"))
        assertEquals("b", working().selectedServices[ServiceRole.TRANSLATOR])
        assertEquals(listOf("a", "c"), working().comparisonTranslatorIds)

        assertEquals(seeded.getActivePreset(), store.state.value.originalConfiguration.getActivePreset())
        store.dispatch(SettingsIntent.CancelChanges)
        assertEquals(seeded.getActivePreset(), store.state.value.workingConfiguration.getActivePreset())

        store.dispatch(SettingsIntent.RemoveTranslatorFromActivePreset("a"))
        awaitSave(store)
        assertEquals(
            listOf("b", "c", "d"),
            store.state.value.originalConfiguration.getActivePreset()!!.translatorSetIds
        )
    }

    @Test
    fun `presets keep independent translator sets`() = runTest {
        val first = translatorConfig("a", "b").getActivePreset()!!
        val second = first.copy(
            id = "second",
            name = "Second",
            selectedServices = first.selectedServices + (ServiceRole.TRANSLATOR to "x"),
            comparisonTranslatorIds = listOf("y", "b")
        )
        val store = store(
            Configuration.DEFAULT.copy(servicePresets = listOf(first, second), activeServicePresetId = first.id)
        )

        store.dispatch(SettingsIntent.RemoveTranslatorFromActivePreset("b"))
        store.dispatch(SettingsIntent.SetActivePreset("second"))
        assertEquals(listOf("x", "y", "b"), store.state.value.workingConfiguration.getActivePreset()!!.translatorSetIds)

        store.dispatch(SettingsIntent.AddTranslatorToActivePreset("z"))
        store.dispatch(SettingsIntent.SetActivePreset(first.id))
        assertEquals(listOf("a"), store.state.value.workingConfiguration.getActivePreset()!!.translatorSetIds)
        assertEquals(
            listOf("x", "y", "b", "z"),
            store.state.value.workingConfiguration.servicePresets.last().translatorSetIds
        )
    }

    @Test
    fun `clearing a service selection stores null so resolution stays automatic`() = runTest {
        val store = store(Configuration.DEFAULT)

        store.dispatch(SettingsIntent.UpdateServiceInActivePreset(ServiceRole.OCR, "some-ocr"))
        store.dispatch(SettingsIntent.UpdateServiceInActivePreset(ServiceRole.OCR, null))

        val selected = store.state.value.workingConfiguration.getActivePreset()!!.selectedServices
        assertTrue(ServiceRole.OCR in selected)
        assertEquals(null, selected[ServiceRole.OCR])
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
    fun `scoped success callback runs after the committed state is published`() = runTest {
        val store = store(Configuration.DEFAULT.copy(extraOutputType = ExtraOutputType.BackwardTranslate))
        var savedType: ExtraOutputType? = null

        store.dispatch(
            SettingsIntent.ToggleSetting(
                update = { it.copy(extraOutputType = ExtraOutputType.Summarize) },
                onSuccess = { saved ->
                    savedType = saved.extraOutputType
                    assertEquals(ExtraOutputType.Summarize, store.state.value.originalConfiguration.extraOutputType)
                },
            )
        )
        awaitSuccessEvent(store)

        assertEquals(ExtraOutputType.Summarize, savedType)
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

    private fun translatorConfig(primary: String, vararg comparisons: String): Configuration {
        val preset = Configuration.DEFAULT.getActivePreset()!!.let {
            it.copy(
                selectedServices = it.selectedServices + (ServiceRole.TRANSLATOR to primary),
                comparisonTranslatorIds = comparisons.toList()
            )
        }
        return Configuration.DEFAULT.copy(servicePresets = listOf(preset), activeServicePresetId = preset.id)
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
