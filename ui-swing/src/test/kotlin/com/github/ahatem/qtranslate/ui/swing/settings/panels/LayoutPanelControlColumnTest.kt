package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.ComponentOrientation
import java.awt.Container
import java.nio.file.Files
import javax.swing.JComboBox
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Layout page's controls all sit in the grid's second column, so the shared column edge is a
 * property of the layout helpers rather than of any one row. The audit reported per-row x values
 * that differed; measured here, every control starts at the same x in both directions.
 */
class LayoutPanelControlColumnTest {

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

    private fun store(): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-layout-store").toFile()
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(Configuration.DEFAULT)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, Configuration.DEFAULT)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun layoutTree(root: Container) {
        root.doLayout()
        root.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    private fun controlEdges(orientation: ComponentOrientation): List<Int> {
        val store = store()
        val panel = onEdt {
            LayoutPanel(store, localizer).also {
                it.applyComponentOrientation(orientation)
                it.render(store.state.value)
                it.setSize(900, 700)
                layoutTree(it)
            }
        }
        return descendants(panel).filterIsInstance<JComboBox<*>>()
            .map { SwingUtilities.convertPoint(it, 0, 0, panel).x }
    }

    @Test
    fun `every combo control starts on one column edge`() {
        val edges = controlEdges(ComponentOrientation.LEFT_TO_RIGHT)
        assertTrue(edges.size >= 5, "the page's five picker rows are measured (${edges.size})")
        assertEquals(
            listOf(edges.first()),
            edges.distinct(),
            "Layout Preset, both service-selector rows and both button rows share one control edge",
        )
    }

    @Test
    fun `the column edge survives right-to-left mirroring`() {
        val edges = controlEdges(ComponentOrientation.RIGHT_TO_LEFT)
        assertTrue(edges.size >= 5)
        assertEquals(
            listOf(edges.first()),
            edges.distinct(),
            "mirroring moves the column but does not scatter the controls",
        )
    }
}
