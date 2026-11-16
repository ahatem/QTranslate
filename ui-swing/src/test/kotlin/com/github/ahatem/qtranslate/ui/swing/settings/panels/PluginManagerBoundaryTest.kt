package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plugin lifecycle operations happen immediately, so they cannot live inside a dialog whose
 * Cancel button implies they can be undone.
 */
class PluginManagerBoundaryTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(
            Files.createTempDirectory("qtranslate-plugin-manager").toFile(),
            LanguageTomlParser(),
            logger,
        )
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    @Test
    fun `the Settings page is a link to the manager, not the manager itself`() {
        var opened = 0
        val panel = onEdt { PluginManagerLinkPanel(localizer) { opened++ } }

        assertEquals(
            0,
            descendants(panel).count { it is PluginsPanel },
            "no lifecycle control sits inside the Apply/Cancel draft",
        )

        val open = descendants(panel)
            .filterIsInstance<JButton>()
            .single { it.text == localizer.getString("settings_plugins.manager_open") }
        onEdt { open.doClick() }
        assertEquals(1, opened, "the page's one action opens the manager")
    }

    @Test
    fun `the entry point is searchable`() {
        val panel = onEdt { PluginManagerLinkPanel(localizer) {} }
        assertTrue(
            panel.searchEntries.any { it.label == localizer.getString("settings_plugins.manager_open") },
            "searching for plugins lands on the way in, not on nothing",
        )
    }
}
