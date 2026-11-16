package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.FlatClientProperties
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JLabel
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
        val panel = onEdt {
            PluginManagerLinkPanel(localizer, onOpenPluginManager = { opened++ })
                .also { it.render(SettingsState(originalConfiguration = Configuration.DEFAULT, workingConfiguration = Configuration.DEFAULT)) }
        }

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
    fun `the page explains what plugins are and where they are managed`() {
        val panel = onEdt {
            PluginManagerLinkPanel(localizer, onOpenPluginManager = {})
                .also { it.render(SettingsState(originalConfiguration = Configuration.DEFAULT, workingConfiguration = Configuration.DEFAULT)) }
        }
        val shown = onEdt { descendants(panel).mapNotNull { it.textOf() } }

        assertTrue(
            shown.any { it.contains("translators") && it.contains("Plugin Manager") },
            "the page says what plugins add and where they are managed: $shown",
        )
        assertTrue(
            shown.any { it.contains(localizer.getString("settings_plugins.manager_hint")) },
            "and that the manager's changes are immediate, so they are not part of Apply/Cancel",
        )
    }
    @Test
    fun `the open action is an ordinary button, so it follows the theme`() {
        val panel = onEdt {
            PluginManagerLinkPanel(localizer, onOpenPluginManager = {})
                .also { it.render(SettingsState(originalConfiguration = Configuration.DEFAULT, workingConfiguration = Configuration.DEFAULT)) }
        }
        val open = onEdt {
            descendants(panel)
                .filterIsInstance<JButton>()
                .single { it.text == localizer.getString("settings_plugins.manager_open") }
        }

        // Nothing about the look is set on the button. An earlier version froze the theme's
        // default-button colours into a style string, which looked right until the user switched
        // theme and the button stayed gold. The theme has to own this.
        assertEquals(null, onEdt { open.getClientProperty(FlatClientProperties.STYLE) }, "no style string")
        assertEquals(null, onEdt { open.getClientProperty(FlatClientProperties.BUTTON_TYPE) }, "no button type")
    }
    @Test
    fun `the installed count is shown when the manager has plugins`() {
        val panel = onEdt { PluginManagerLinkPanel(localizer, onOpenPluginManager = {}, pluginSummary = { "3 installed · 2 enabled" }) }
        onEdt { panel.render(SettingsState(originalConfiguration = Configuration.DEFAULT, workingConfiguration = Configuration.DEFAULT)) }

        val status = onEdt { descendants(panel).filterIsInstance<JLabel>().single { it.text == "3 installed · 2 enabled" } }
        assertTrue(onEdt { status.isVisible }, "a page with state says what the state is")
    }

    @Test
    fun `the entry point is searchable`() {
        val panel = onEdt { PluginManagerLinkPanel(localizer, onOpenPluginManager = {}) }
        assertTrue(
            panel.searchEntries.any { it.label == localizer.getString("settings_plugins.manager_open") },
            "searching for plugins lands on the way in, not on nothing",
        )
    }
}
