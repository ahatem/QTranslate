package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.IntelliJTheme
import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsState
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.UIManager
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
    fun `the open action is the page's primary button`() {
        val panel = onEdt {
            PluginManagerLinkPanel(localizer, onOpenPluginManager = {})
                .also { it.render(SettingsState(originalConfiguration = Configuration.DEFAULT, workingConfiguration = Configuration.DEFAULT)) }
        }
        val open = onEdt {
            descendants(panel)
                .filterIsInstance<JButton>()
                .single { it.text == localizer.getString("settings_plugins.manager_open") }
        }

        assertTrue(
            (onEdt { open.getClientProperty(FlatClientProperties.STYLE) } as? String)
                ?.contains("background: #") == true,
            "the action is styled from the theme rather than left as an ordinary button",
        )
        assertTrue(
            onEdt { open.preferredSize.height } >= SettingsPanel.PRIMARY_BUTTON_HEIGHT,
            "and it is taller than a row control, so it reads as the page's action",
        )
    }

    /**
     * The primary action is actually painted in the theme's accent.
     *
     * `BUTTON_TYPE = "default"` looks like the way to do this and is not: FlatLaf ignores unknown
     * button types without complaint, so the button painted as an ordinary one. This measures the
     * pixels instead of trusting the property, using the application's own theme so the assertion
     * is about the identity's gold rather than a test-only colour.
     */
    @Test
    fun `the open action is painted in the theme's default-button color`() {
        val previous = UIManager.getLookAndFeel()
        try {
            onEdt {
                val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("themes/qtranslate-light.theme.json"))
                stream.use { FlatLaf.setup(IntelliJTheme.createLaf(it)) }
            }
            val accent = onEdt { UIManager.getColor("Button.default.background") }
            val panel = onEdt {
                PluginManagerLinkPanel(localizer, onOpenPluginManager = {})
                    .also { it.render(SettingsState(originalConfiguration = Configuration.DEFAULT, workingConfiguration = Configuration.DEFAULT)) }
            }
            val open = onEdt {
                descendants(panel)
                    .filterIsInstance<JButton>()
                    .single { it.text == localizer.getString("settings_plugins.manager_open") }
            }

            val painted = onEdt { fillColorOf(open) }

            assertEquals(accent.rgb, painted, "the button is filled with the theme's accent, not the page colour")
            assertTrue(
                painted != onEdt { panel.background.rgb },
                "and the fill is distinguishable from the page behind it",
            )
        } finally {
            previous?.let { laf -> onEdt { UIManager.setLookAndFeel(laf) } }
        }
    }

    /** Paints [button] and returns the colour it fills the body with, away from its label. */
    private fun fillColorOf(button: JButton): Int {
        button.setSize(400, 40)
        val image = BufferedImage(400, 40, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            button.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image.getRGB(8, 6)
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
