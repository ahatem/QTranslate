package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.TranslationCapabilities
import javax.swing.JLabel
import javax.swing.JList
import com.github.ahatem.qtranslate.ui.swing.testing.onEdt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Extra Output only offers what the application can run; a missing service is a "set up" action. */
class TranslationPanelCapabilityTest {

    private val fixture = SettingsPanelFixture()
    private val localizer get() = fixture.localizer

    @AfterTest
    fun tearDown() = fixture.close()

    private fun store(config: Configuration): SettingsStore = fixture.store(config)

    private fun panel(
        store: SettingsStore,
        capabilities: TranslationCapabilities,
        onOpenServices: () -> Unit = {},
    ): TranslationPanel = onEdt {
        TranslationPanel(
            store,
            localizer,
            translationCapabilities = { capabilities },
            onOpenServices = onOpenServices,
        ).also { it.render(store.state.value) }
    }

    private fun indexOfType(panel: TranslationPanel, ordinal: Int): Int {
        val combo = panel.typeComboForTest()
        return (0 until combo.itemCount).first { index ->
            // The private choice type is not visible here; the ordinal of ExtraOutputType.None,
            // BackwardTranslate, Summarize, Rewrite matches the combo's own order.
            combo.getItemAt(index).toString().contains(
                listOf("None", "BackwardTranslate", "Summarize", "Rewrite")[ordinal]
            )
        }
    }

    /** One combo entry as the closed picker shows it; the renderer's wildcard type is widened here. */
    @Suppress("UNCHECKED_CAST")
    private fun renderedCell(combo: javax.swing.JComboBox<*>, index: Int): JLabel {
        val renderer = combo.renderer as javax.swing.ListCellRenderer<Any?>
        return renderer.getListCellRendererComponent(
            JList<Any?>(),
            combo.getItemAt(index),
            index,
            false,
            false,
        ) as JLabel
    }

    @Test
    fun `a missing summarizer is an unavailable action that leads to Services`() {
        val store = store(Configuration.FRESH_INSTALL)
        var openedServices = 0
        val translation = panel(
            store,
            TranslationCapabilities(summarizerAvailable = false, rewriterAvailable = false),
            onOpenServices = { openedServices++ },
        )

        val combo = translation.typeComboForTest()
        val summarizeIndex = indexOfType(translation, 2)
        val rendered = renderedCell(combo, summarizeIndex)

        assertEquals(
            localizer.getString("settings_translation.type_summarize_unavailable"),
            rendered.text,
            "the entry says what to do about it rather than just greying out",
        )
        assertEquals(
            localizer.getString("settings_translation.type_unavailable_hint"),
            rendered.toolTipText,
            "and says why",
        )

        onEdt { combo.selectedIndex = summarizeIndex }

        assertEquals(
            ExtraOutputType.None,
            store.state.value.workingConfiguration.extraOutputType,
            "an unusable type is never written to the draft",
        )
        assertEquals(1, openedServices, "clicking it takes the user where the service is configured")
    }

    @Test
    fun `a missing rewriter is presented the same way as a missing summarizer`() {
        val translation = panel(
            store(Configuration.FRESH_INSTALL),
            TranslationCapabilities(summarizerAvailable = true, rewriterAvailable = false),
        )
        val rendered = renderedCell(translation.typeComboForTest(), indexOfType(translation, 3))

        assertEquals(localizer.getString("settings_translation.type_rewrite_unavailable"), rendered.text)
    }

    @Test
    fun `an available summarizer is selectable and saved`() {
        val store = store(Configuration.FRESH_INSTALL)
        val translation = panel(
            store,
            TranslationCapabilities(summarizerAvailable = true, rewriterAvailable = false),
        )

        onEdt { translation.typeComboForTest().selectedIndex = indexOfType(translation, 2) }

        assertEquals(
            ExtraOutputType.Summarize,
            store.state.value.workingConfiguration.extraOutputType,
        )
    }

    @Test
    fun `the source choice is disabled for backward translation and live for other extra outputs`() {
        val backward = panel(
            store(Configuration.FRESH_INSTALL.copy(extraOutputType = ExtraOutputType.BackwardTranslate)),
            TranslationCapabilities.UNKNOWN,
        )
        assertFalse(onEdt { backward.useInputForTest().isEnabled }, "the runtime always reads the translated text")
        assertEquals(
            localizer.getString("settings_translation.backward_input_unavailable"),
            onEdt { backward.useInputForTest().toolTipText },
        )
        assertTrue(onEdt { backward.useTranslatedForTest().isSelected }, "the radio shows what the runtime uses")

        val summarize = panel(
            store(Configuration.FRESH_INSTALL.copy(extraOutputType = ExtraOutputType.Summarize)),
            TranslationCapabilities.UNKNOWN,
        )
        assertTrue(onEdt { summarize.useInputForTest().isEnabled })
        assertNull(onEdt { summarize.useInputForTest().toolTipText })
    }
}
