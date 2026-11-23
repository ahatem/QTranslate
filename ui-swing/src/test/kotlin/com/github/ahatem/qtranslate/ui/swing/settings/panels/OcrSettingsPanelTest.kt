package com.github.ahatem.qtranslate.ui.swing.settings.panels

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
import com.github.ahatem.qtranslate.core.settings.data.OcrTranslationDestination
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
import javax.swing.JButton
import com.github.ahatem.qtranslate.ui.swing.testing.SilentLogger
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OcrSettingsPanelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-ocr-settings").toFile(), LanguageTomlParser(), SilentLogger)
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `choosing translate after selection writes the draft and keeps the destination usable`() {
        val fixture = onEdt { fixture() }
        onEdt { fixture.panel.render(fixture.store.state.value) }

        assertEquals(0, onEdt { fixture.panel.postActionComboForTest().selectedIndex })
        assertTrue(onEdt { fixture.panel.destinationComboForTest().isEnabled }, "explicit translate hotkeys need the destination")

        onEdt {
            fixture.panel.postActionComboForTest().selectedIndex = 1
            fixture.panel.render(fixture.store.state.value)
        }

        assertEquals(OcrPostSelectionAction.TRANSLATE, fixture.store.state.value.workingConfiguration.ocr.postSelectionAction)
        assertTrue(onEdt { fixture.panel.destinationComboForTest().isEnabled })
    }

    @Test
    fun `the destination, line-break and annotation options write the draft`() {
        val fixture = onEdt { fixture() }
        onEdt { fixture.panel.render(fixture.store.state.value) }
        assertFalse(onEdt { fixture.panel.removeLineBreaksForTest().isSelected })

        onEdt { fixture.panel.removeLineBreaksForTest().doClick() }
        onEdt { fixture.panel.destinationComboForTest().selectedIndex = 1 }
        onEdt { fixture.panel.annotationsForTest().doClick() }

        val ocr = fixture.store.state.value.workingConfiguration.ocr
        assertTrue(ocr.removeLineBreaks)
        assertEquals(OcrTranslationDestination.QUICK_TRANSLATE, ocr.translationDestination)
        assertFalse(ocr.showWorkspaceAnnotations)
    }

    @Test
    fun `the OCR service is shown read-only, with the way to its single owner`() {
        var openedServices = 0
        val fixture = onEdt { fixture(onOpenServices = { openedServices++ }) }
        onEdt { fixture.panel.render(fixture.store.state.value) }

        assertEquals(
            "First OCR",
            fixture.panel.providerSummaryForTest().text,
            "the page says which service is in force",
        )
        assertEquals(
            "first-ocr",
            fixture.store.state.value.workingConfiguration.getActivePreset()
                ?.selectedServices?.get(ServiceRole.OCR),
            "rendering the page never rewrites the preset's choice",
        )

        val change = onEdt {
            descendants(fixture.panel).filterIsInstance<JButton>()
                .single { it.text == localizer.getString("settings_ocr.provider_change_in_services") }
        }
        onEdt { change.doClick() }

        assertEquals(1, openedServices, "the only editor is Services & Presets, and this is the way to it")
    }

    private data class Fixture(val panel: OcrSettingsPanel, val store: SettingsStore)

    private fun fixture(onOpenServices: () -> Unit = {}): Fixture = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-ocr-settings-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, SilentLogger)
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
        val store = SettingsStore(repository, SilentLogger, scope, configuration)
        val services = mapOf<String, Service>(
            "first-ocr" to FakeOcr("first-ocr", "First OCR"),
            "second-ocr" to FakeOcr("second-ocr", "Second OCR"),
        )
        val catalog = OcrServiceCatalog(
            activeServices = MutableStateFlow(services),
            configuration = MutableStateFlow(configuration),
            scope = scope,
            logger = SilentLogger,
        )
        val panel = OcrSettingsPanel(
            store, localizer, catalog, scope,
            onOpenHotkeys = {},
            onOpenServices = onOpenServices,
        )
        Fixture(panel, store)
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
