package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.PaneLayout
import com.github.ahatem.qtranslate.core.settings.data.RegionSlot
import com.github.ahatem.qtranslate.core.settings.data.ServicePreset
import com.github.ahatem.qtranslate.core.settings.data.ServiceWidget
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.data.WindowLayout
import com.github.ahatem.qtranslate.core.settings.data.withWindowLayout
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsIntent
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
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Layout page exposes every region's real choice, and gates only the values that would
 * otherwise be dead: the selector's position and style while a comparison board owns switching,
 * and the strip labels unless the strip is the widget. Each gated row says why.
 */
class LayoutPanelCapabilityTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-layout-test").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(config: Configuration): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-layout-store").toFile()
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

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    private fun rowCombo(panel: Container, label: String): JComboBox<*> {
        val rowLabel = descendants(panel).filterIsInstance<JLabel>().single { it.text == label }
        return rowLabel.labelFor as JComboBox<*>
    }

    private fun panel(store: SettingsStore, translatorIds: List<String> = emptyList()): LayoutPanel = onEdt {
        LayoutPanel(store, localizer, availableTranslatorIds = { translatorIds }).also { it.render(store.state.value) }
    }

    private fun writeLayout(store: SettingsStore, panel: LayoutPanel, transform: (WindowLayout) -> WindowLayout) =
        onEdt {
            store.dispatch(SettingsIntent.TransformDraft { it.withWindowLayout(transform(it.windowLayout)) })
            panel.render(store.state.value)
        }

    /** A configuration with two usable translators, so a comparison board is really built. */
    private val comparisonReady = Configuration.DEFAULT.copy(
        servicePresets = listOf(
            ServicePreset(
                id = "preset",
                name = "preset",
                selectedServices = mapOf(ServiceRole.TRANSLATOR to "google"),
                comparisonTranslatorIds = listOf("bing"),
            )
        ),
        activeServicePresetId = "preset",
    )

    @Test
    fun `strip labels are only offered while the strip is the selector`() {
        val store = store(Configuration.DEFAULT)
        val layoutPanel = panel(store)
        val appearance = rowCombo(layoutPanel, localizer.getString("settings_window.layout_row_appearance"))

        // The default picker has no strip, so the appearance value would change nothing.
        assertFalse(appearance.isEnabled, "a picker has no strip whose labels can change")
        assertEquals(
            localizer.getString("settings_window.layout_appearance_needs_strip"),
            appearance.toolTipText,
            "the disabled row says why",
        )

        writeLayout(store, layoutPanel) { it.copy(serviceWidget = ServiceWidget.STRIP) }
        assertTrue(appearance.isEnabled, "the strip's labels can now be tuned")
    }

    @Test
    fun `a comparison board gates the selector rows and keeps their values`() {
        val store = store(comparisonReady.withWindowLayout(WindowLayout(services = RegionSlot.TOP)))
        val layoutPanel = panel(store, translatorIds = listOf("google", "bing"))
        val position = rowCombo(layoutPanel, localizer.getString("settings_window.layout_row_position"))
        val style = rowCombo(layoutPanel, localizer.getString("settings_window.layout_row_selector"))
        assertTrue(position.isEnabled && style.isEnabled, "stacked panes have a selector to place")

        writeLayout(store, layoutPanel) { it.copy(panes = PaneLayout.COMPARISON) }
        assertFalse(position.isEnabled, "the board header switches the translator under comparison")
        assertFalse(style.isEnabled)
        assertEquals(localizer.getString("settings_window.layout_selector_absent"), position.toolTipText)
        assertEquals(
            RegionSlot.TOP,
            store.state.value.workingConfiguration.windowLayout.services,
            "the stored position is kept for when the panes change back",
        )
    }

    @Test
    fun `an ineligible comparison leaves the selector rows usable`() {
        // Without two translators the window builds stacked panes, so the selector is really there.
        val store = store(Configuration.DEFAULT.withWindowLayout(WindowLayout(panes = PaneLayout.COMPARISON)))
        val layoutPanel = panel(store)
        val position = rowCombo(layoutPanel, localizer.getString("settings_window.layout_row_position"))
        assertTrue(position.isEnabled, "the effective panes decide, not the stored preference")
    }

    @Test
    fun `history placement survives a change to comparison`() {
        val store = store(Configuration.DEFAULT)
        val layoutPanel = panel(store)
        val history = rowCombo(layoutPanel, localizer.getString("settings_window.layout_row_history"))

        writeLayout(store, layoutPanel) { it.copy(history = RegionSlot.BOTTOM) }
        assertEquals(
            RegionSlot.BOTTOM,
            store.state.value.workingConfiguration.windowLayout.history,
        )
        assertTrue(history.isEnabled, "every pane layout has a history placement to choose")

        writeLayout(store, layoutPanel) { it.copy(panes = PaneLayout.COMPARISON) }
        assertEquals(
            RegionSlot.BOTTOM,
            store.state.value.workingConfiguration.windowLayout.history,
            "changing panes does not silently reset the history choice",
        )
    }
}
