package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.DictionaryAutoSource
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import javax.swing.JCheckBox
import javax.swing.JRadioButton
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dictionary automatic lookup: one master switch, and no second control pretending to be one.
 *
 * The page used to offer a "show floating popup for single words" checkbox whose value the
 * coordinator read as the master gate for every kind of lookup — so the master checkbox did
 * nothing and the popup checkbox silently disabled inline and docked lookup. The coordinator's
 * own tests cover the runtime side; this covers what the page offers.
 */
class TranslationPanelDictionaryTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(
            Files.createTempDirectory("qtranslate-dictionary-settings").toFile(),
            LanguageTomlParser(),
            logger,
        )
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(config: Configuration): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-dictionary-settings-store").toFile()
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

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

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
        // The dialog re-renders the page from state, which is where the radios are disabled.
        onEdt { translation.render(store.state.value) }
        // Nothing to choose while lookup is off, so the source radios go with it.
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
