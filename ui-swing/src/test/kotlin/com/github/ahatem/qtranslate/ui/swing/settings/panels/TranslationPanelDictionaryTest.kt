package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import javax.swing.JCheckBox
import javax.swing.JRadioButton
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Dictionary automatic lookup has one master switch; the source radios follow it. */
class TranslationPanelDictionaryTest {

    private val fixture = SettingsPanelFixture()
    private val localizer get() = fixture.localizer

    @AfterTest
    fun tearDown() = fixture.close()

    private fun store(config: Configuration): SettingsStore = fixture.store(config)

    private fun panel(store: SettingsStore): TranslationPanel = onEdt {
        TranslationPanel(store, localizer).also { it.render(store.state.value) }
    }

    @Test
    fun `the master checkbox turns automatic lookup off`() {
        val store = store(Configuration.DEFAULT.copy(dictionaryAutoSource = DictionaryAutoSource.TRANSLATED))
        val translation = panel(store)
        val master = descendants(translation)
            .filterIsInstance<JCheckBox>()
            .single { it.text == localizer.getString("settings_translation.dict_auto_lookup_enabled") }

        assertTrue(master.isSelected, "the stored value is reflected")
        onEdt { master.doClick() }

        assertEquals(
            DictionaryAutoSource.OFF,
            store.state.value.workingConfiguration.dictionaryAutoSource,
        )
        onEdt { translation.render(store.state.value) }
        assertTrue(
            descendants(translation).filterIsInstance<JRadioButton>().none { it.isEnabled },
            "the source choice is meaningless while automatic lookup is off",
        )
    }

    @Test
    fun `turning it back on restores the last source choice`() {
        val store = store(Configuration.DEFAULT.copy(dictionaryAutoSource = DictionaryAutoSource.SOURCE))
        val translation = panel(store)
        val master = descendants(translation)
            .filterIsInstance<JCheckBox>()
            .single { it.text == localizer.getString("settings_translation.dict_auto_lookup_enabled") }

        onEdt { master.doClick() }
        assertEquals(DictionaryAutoSource.OFF, store.state.value.workingConfiguration.dictionaryAutoSource)

        onEdt { master.doClick() }
        assertEquals(
            DictionaryAutoSource.SOURCE,
            store.state.value.workingConfiguration.dictionaryAutoSource,
            "the radio that was last chosen is restored, not a fixed default",
        )
    }
}
