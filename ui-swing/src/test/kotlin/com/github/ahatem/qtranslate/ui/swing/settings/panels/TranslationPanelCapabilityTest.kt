package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ExtraOutputType
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.TranslationCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Extra Output must only offer what the application can run.
 *
 * Summarize and Rewrite were selectable with no such service installed, so choosing one produced
 * a failure later. They now appear as a dimmed "set up" action that leads to the page where the
 * missing service belongs, and a choice the runtime ignores is disabled rather than accepted.
 */
class TranslationPanelCapabilityTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(
            Files.createTempDirectory("qtranslate-translation-capability").toFile(),
            LanguageTomlParser(),
            logger,
        )
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(config: Configuration): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-translation-capability-store").toFile()
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

    /**
     * Renders one combo entry exactly as the closed picker would show it.
     *
     * The combo's renderer is typed with a captured wildcard, so it is widened here rather than
     * making the production renderer's type public for a test.
     */
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
    fun `a missing rewriter is presented the same way`() {
        val translation = panel(
            store(Configuration.FRESH_INSTALL),
            TranslationCapabilities(summarizerAvailable = true, rewriterAvailable = false),
        )
        val combo = translation.typeComboForTest()
        val rewriteIndex = indexOfType(translation, 3)
        val rendered = renderedCell(combo, rewriteIndex)

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
    fun `backward translation disables the source choice the runtime ignores`() {
        val store = store(Configuration.FRESH_INSTALL.copy(extraOutputType = ExtraOutputType.BackwardTranslate))
        val translation = panel(store, TranslationCapabilities.UNKNOWN)

        assertFalse(
            onEdt { translation.useInputForTest().isEnabled },
            "the runtime always reads the translated text for backward translation",
        )
        assertEquals(
            localizer.getString("settings_translation.backward_input_unavailable"),
            onEdt { translation.useInputForTest().toolTipText },
            "the disabled choice explains itself",
        )
        assertTrue(
            onEdt { translation.useTranslatedForTest().isSelected },
            "the radio shows what the runtime will actually use",
        )
    }

    @Test
    fun `the source choice is live for other extra outputs`() {
        val store = store(Configuration.FRESH_INSTALL.copy(extraOutputType = ExtraOutputType.Summarize))
        val translation = panel(store, TranslationCapabilities.UNKNOWN)

        assertTrue(onEdt { translation.useInputForTest().isEnabled })
        assertNull(onEdt { translation.useInputForTest().toolTipText })
    }
}
