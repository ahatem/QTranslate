package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoCopySettingPanelTest {
    private val fixture = SettingsPanelFixture()

    @AfterTest
    fun tearDown() = fixture.close()

    private fun combos(root: Component): List<JComboBox<*>> =
        (if (root is JComboBox<*>) listOf(root) else emptyList()) +
            if (root is Container) root.components.flatMap(::combos) else emptyList()

    /** The panel's row class is private; its `toString` is the only way to read the mode back. */
    private fun JComboBox<*>.indexOfMode(mode: AutoCopyTranslation): Int =
        (0 until itemCount).indexOfFirst { getItemAt(it).toString().startsWith("AutoCopyTranslationInfo(mode=$mode,") }

    @Test
    fun `choosing a mode writes it to the draft`() {
        val store = fixture.store()
        val panel = onEdt { TranslationPanel(store, fixture.localizer) }
        onEdt { panel.render(store.state.value) }
        val combo = onEdt { combos(panel).first { it.indexOfMode(AutoCopyTranslation.ALL) >= 0 } }

        onEdt { combo.selectedIndex = combo.indexOfMode(AutoCopyTranslation.ALL) }

        assertEquals(
            AutoCopyTranslation.ALL,
            store.state.value.workingConfiguration.autoCopyTranslation,
            "the selection is a draft change, saved with the rest of the settings",
        )
    }
}
