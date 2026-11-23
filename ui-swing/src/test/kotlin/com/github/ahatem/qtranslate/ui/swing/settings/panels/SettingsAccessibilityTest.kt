package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.JTextField
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A row's label must name its control, or a screen reader announces a word and then an unlabelled field. */
class SettingsAccessibilityTest {

    private val fixture = SettingsPanelFixture()
    private val localizer get() = fixture.localizer

    @AfterTest
    fun tearDown() = fixture.close()

    private fun store(): SettingsStore = fixture.store(Configuration.FRESH_INSTALL)

    private fun labelText(panel: Container, text: String): JLabel =
        descendants(panel).filterIsInstance<JLabel>().first { it.text == text }

    @Test
    fun `a row label names the control beside it`() {
        val store = store()
        val panel = onEdt {
            GeneralPanel(
                store,
                localizer,
                fixture.newScope(),
            ).also { panel -> panel.render(store.state.value) }
        }

        val readAloud = labelText(panel, localizer.getString("settings_general.selection_read_aloud"))
        assertNotNull(readAloud.labelFor, "the label is associated with its picker")
        assertTrue(readAloud.labelFor is JComboBox<*>, "and it names the picker, not the page")

        val network = onEdt { NetworkPanel(store, localizer).also { it.render(store.state.value) } }
        val proxyAddress = labelText(network, localizer.getString("settings_network.proxy_url"))
        assertTrue(proxyAddress.labelFor is JTextField, "the proxy address label points at the address field")
    }

    @Test
    fun `an icon-only row action carries a name for assistive technology`() {
        val store = store()
        val panel = onEdt {
            GeneralPanel(
                store,
                localizer,
                fixture.newScope(),
                openEditor = { },
            ).also { panel -> panel.render(store.state.value) }
        }

        val iconOnly = descendants(panel)
            .filterIsInstance<JButton>()
            .filter { it.text.isNullOrBlank() && !it.toolTipText.isNullOrBlank() }

        assertTrue(iconOnly.isNotEmpty(), "the language row has icon-only actions")
        assertTrue(
            iconOnly.all { !it.accessibleContext.accessibleName.isNullOrBlank() },
            "a glyph with only a tooltip is unlabelled to a screen reader",
        )
    }
}
