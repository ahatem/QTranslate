package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.ocr.OcrServiceCatalog
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.OcrPostSelectionAction
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import javax.swing.JCheckBox
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcrSettingsPanelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-ocr-settings").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `the page shows the compatible defaults and manages dependent controls`() {
        val fixture = onEdt { fixture() }

        onEdt {
            fixture.panel.render(fixture.store.state.value)
            assertEquals(0, fixture.panel.postActionComboForTest().selectedIndex)
            assertTrue(fixture.panel.destinationComboForTest().isEnabled, "explicit translate hotkeys need this destination")
            assertTrue(fixture.panel.providerComboForTest().itemCount >= 2)
            assertTrue(fixture.panel.languageComboForTest().itemCount >= 2)
            assertFalse(fixture.panel.removeLineBreaksForTest().isSelected)

            fixture.panel.postActionComboForTest().selectedIndex = 1
            fixture.panel.render(fixture.store.state.value)

            assertEquals(
                OcrPostSelectionAction.TRANSLATE,
                fixture.store.state.value.workingConfiguration.ocr.postSelectionAction,
            )
            assertTrue(fixture.panel.destinationComboForTest().isEnabled)

            fixture.panel.removeLineBreaksForTest().doClick()
            assertTrue(fixture.store.state.value.workingConfiguration.ocr.removeLineBreaks)

            fixture.panel.destinationComboForTest().selectedIndex = 1
            assertEquals(
                com.github.ahatem.qtranslate.core.settings.data.OcrTranslationDestination.QUICK_TRANSLATE,
                fixture.store.state.value.workingConfiguration.ocr.translationDestination,
            )

            fixture.panel.annotationsForTest().doClick()
            assertFalse(fixture.store.state.value.workingConfiguration.ocr.showWorkspaceAnnotations)

            fixture.panel.providerComboForTest().selectedIndex = 0
            assertEquals(
                null,
                fixture.store.state.value.workingConfiguration.getActivePreset()
                    ?.selectedServices?.get(ServiceRole.OCR),
            )
        }
    }

    @Test
    fun `changing the provider writes the existing preset OCR selection`() {
        val fixture = onEdt { fixture() }
        onEdt {
            fixture.panel.render(fixture.store.state.value)
            fixture.panel.providerComboForTest().selectedIndex = 2
            fixture.panel.render(fixture.store.state.value)
        }

        assertEquals(
            "second-ocr",
            fixture.store.state.value.workingConfiguration.getActivePreset()
                ?.selectedServices?.get(ServiceRole.OCR),
        )
    }

    private data class Fixture(val panel: OcrSettingsPanel, val store: SettingsStore)

    private fun fixture(): Fixture = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-ocr-settings-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        val preset = ServicePreset(
            id = "preset",
            name = "preset",
            selectedServices = mapOf(ServiceRole.OCR to "first-ocr"),
        )
        val configuration = Configuration.DEFAULT.copy(
            servicePresets = listOf(preset),
            activeServicePresetId = preset.id,
        )
        repository.updateConfiguration(configuration)
        val store = SettingsStore(repository, logger, scope, configuration)
        val services = mapOf<String, Service>(
            "first-ocr" to FakeOcr("first-ocr", "First OCR"),
            "second-ocr" to FakeOcr("second-ocr", "Second OCR"),
        )
        val catalog = OcrServiceCatalog(
            activeServices = MutableStateFlow(services),
            configuration = MutableStateFlow(configuration),
            scope = scope,
            logger = logger,
        )
        val panel = OcrSettingsPanel(store, localizer, catalog, scope, onOpenHotkeys = {})
        Fixture(panel, store)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private class FakeOcr(
        override val key: String,
        override val name: String,
    ) : OCR {
        override val version = "test"
        override val supportedLanguages = SupportedLanguages.Specific(setOf(LanguageCode.ENGLISH))
        override suspend fun extractText(request: com.github.ahatem.qtranslate.api.ocr.OCRRequest) =
            com.github.michaelbull.result.Ok(com.github.ahatem.qtranslate.api.ocr.OCRResponse("text"))
    }
}
