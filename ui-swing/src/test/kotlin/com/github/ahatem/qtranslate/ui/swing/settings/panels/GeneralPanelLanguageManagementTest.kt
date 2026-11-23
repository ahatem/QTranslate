package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.io.File
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GeneralPanelLanguageManagementTest {
    private val fixture = SettingsPanelFixture()
    private var frame: JFrame? = null

    @AfterTest
    fun tearDown() {
        onEdt { frame?.dispose() }
        fixture.close()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `deleting the active language persists the fallback instead of staging it`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a windowing toolkit")
        val localizer = fixture.localizer
        File(localizer.languagesDirectory, "fr.toml").also { it.parentFile.mkdirs() }.writeText(
            "[meta]\nname = \"French\"\nnative_name = \"Francais\"\nlocale = \"fr\"\ntranslators = []\nrtl = false\n",
        )
        val store = fixture.store(Configuration.DEFAULT.copy(interfaceLanguage = "fr"))
        val panel = onEdt {
            GeneralPanel(store, localizer, fixture.newScope(), openEditor = {}).also { it.render(store.state.value) }
        }
        frame = onEdt { JFrame().apply { add(panel); pack(); isVisible = true } }

        waitUntil("the language list") {
            onEdt {
                descendants(panel).filterIsInstance<JComboBox<*>>().any { it.selectedItem?.toString()?.contains("French") == true }
            }
        }

        val more = onEdt {
            descendants(panel).filterIsInstance<JButton>()
                .single { it.toolTipText == localizer.getString("settings_appearance.more_actions") }
        }
        onEdt { more.doClick() }
        val delete = onEdt {
            Window.getWindows().filter { it.isShowing }.flatMap { descendants(it) }.filterIsInstance<JMenuItem>()
                .single { it.text == localizer.getString("settings_appearance.menu_delete") }
        }
        // The confirmation is modal, so the click must not block this thread.
        SwingUtilities.invokeLater { delete.doClick() }
        waitUntil("the confirmation dialog") { onEdt { confirmation() != null } }
        onEdt { confirmation()!!.setValue(JOptionPane.YES_OPTION) }

        waitUntil("the fallback to be saved") { store.state.value.originalConfiguration.interfaceLanguage == "en" }
        assertEquals("en", store.state.value.workingConfiguration.interfaceLanguage)
        assertFalse(store.state.value.isDirty, "the fallback is persisted, not left as an unsaved draft")
        assertFalse(File(localizer.languagesDirectory, "fr.toml").exists(), "the translation file is gone")
    }

    private fun confirmation(): JOptionPane? =
        Window.getWindows().filter { it.isShowing }
            .flatMap { descendants(it) }
            .filterIsInstance<JOptionPane>()
            .firstOrNull()
}
