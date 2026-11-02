package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.OcrTranslationDestination
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The OCR destination is a draft setting like the rest of the Translation page, and its two
 * product choices are what the user actually sees.
 */
class OcrDestinationSettingPanelTest {
    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-ocr-destination-panel").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun newStore(config: Configuration = Configuration.DEFAULT): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-ocr-destination-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, config)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun combos(root: Component): List<JComboBox<*>> =
        (if (root is JComboBox<*>) listOf(root) else emptyList()) +
            if (root is Container) root.components.flatMap(::combos) else emptyList()

    private fun JComboBox<*>.indexOfDestination(destination: OcrTranslationDestination): Int =
        (0 until itemCount).indexOfFirst {
            getItemAt(it).toString().startsWith("OcrDestInfo(dest=$destination,")
        }

    private fun destinationCombo(panel: SettingsPanel): JComboBox<*> =
        onEdt { combos(panel).first { it.indexOfDestination(OcrTranslationDestination.MAIN_WINDOW) >= 0 } }

    @Suppress("UNCHECKED_CAST")
    private fun JComboBox<*>.rowTextAt(index: Int): String {
        val list = JList<Any?>()
        val renderer = renderer as ListCellRenderer<Any?>
        return (renderer.getListCellRendererComponent(list, getItemAt(index), index, false, false) as JLabel).text
    }

    private fun JComboBox<*>.destinationAt(index: Int): String =
        getItemAt(index).toString().substringAfter("dest=").substringBefore(",")

    @Test
    fun `the setting offers exactly the two destinations`() {
        val panel = onEdt { TranslationPanel(newStore(), localizer) }
        val combo = destinationCombo(panel)

        assertEquals(2, onEdt { combo.itemCount })
        assertEquals(
            listOf("Popup window", "Main window"),
            onEdt { (0 until combo.itemCount).map { combo.rowTextAt(it) } }
        )
        assertEquals(
            listOf("POPUP", "MAIN_WINDOW"),
            onEdt { (0 until combo.itemCount).map { combo.destinationAt(it) } }
        )
    }

    @Test
    fun `the default destination is shown for a fresh configuration`() {
        val store = newStore()
        val panel = onEdt { TranslationPanel(store, localizer) }
        onEdt { panel.render(store.state.value) }
        val combo = destinationCombo(panel)

        assertEquals(OcrTranslationDestination.MAIN_WINDOW.name, onEdt { combo.destinationAt(combo.selectedIndex) })
    }

    @Test
    fun `choosing a destination writes the draft and leaves the saved value alone`() {
        val store = newStore()
        val panel = onEdt { TranslationPanel(store, localizer) }
        onEdt { panel.render(store.state.value) }
        val combo = destinationCombo(panel)

        val index = onEdt { combo.indexOfDestination(OcrTranslationDestination.POPUP) }
        onEdt { combo.selectedIndex = index }

        assertEquals(
            OcrTranslationDestination.POPUP,
            store.state.value.workingConfiguration.ocrTranslationDestination,
            "the selection is a draft change, saved with the rest of the settings"
        )
        assertEquals(
            OcrTranslationDestination.MAIN_WINDOW,
            store.state.value.originalConfiguration.ocrTranslationDestination,
            "nothing is persisted before Apply/Save"
        )
    }

    @Test
    fun `every label the setting uses is localized`() {
        for (key in listOf("ocr_destination", "ocr_destination_popup", "ocr_destination_main", "ocr_destination_hint")) {
            val value = localizer.getString("settings_translation.$key")
            assertTrue(value.isNotBlank() && !value.startsWith("settings_translation."), "$key is unresolved: $value")
        }
    }
}
