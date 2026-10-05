package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.AutoCopyTranslation
import com.github.ahatem.qtranslate.core.settings.data.Configuration
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
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The auto-copy setting is a three-way choice, and the label of each row is the whole of what
 * the user is told about it — so the words matter as much as the value they write.
 */
class AutoCopySettingPanelTest {
    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(Files.createTempDirectory("qtranslate-auto-copy-panel").toFile(), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun newStore(config: Configuration = Configuration.DEFAULT): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-auto-copy-store").toFile()
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

    private fun combos(root: Component): List<JComboBox<*>> =
        (if (root is JComboBox<*>) listOf(root) else emptyList()) +
            if (root is Container) root.components.flatMap(::combos) else emptyList()

    /** The private row class carries the mode in its own toString, as the layout rows do. */
    private fun JComboBox<*>.indexOfMode(mode: AutoCopyTranslation): Int =
        (0 until itemCount).indexOfFirst { getItemAt(it).toString().startsWith("AutoCopyTranslationInfo(mode=$mode,") }

    private fun autoCopyCombo(panel: SettingsPanel): JComboBox<*> =
        onEdt { combos(panel).first { it.indexOfMode(AutoCopyTranslation.ALL) >= 0 } }

    @Suppress("UNCHECKED_CAST")
    private fun JComboBox<*>.rowTextAt(index: Int): String {
        val list = JList<Any?>()
        val renderer = renderer as ListCellRenderer<Any?>
        return (renderer.getListCellRendererComponent(list, getItemAt(index), index, false, false) as JLabel).text
    }

    private fun JComboBox<*>.modeAt(index: Int): String =
        getItemAt(index).toString().substringAfter("mode=").substringBefore(",")

    @Test
    fun `the setting offers exactly the three product choices`() {
        val panel = onEdt { TranslationPanel(newStore(), localizer) }
        val combo = autoCopyCombo(panel)

        assertEquals(3, onEdt { combo.itemCount })
        assertEquals(
            listOf("Off", "Quick Translate only", "All translations"),
            onEdt { (0 until combo.itemCount).map { combo.rowTextAt(it) } }
        )
        assertEquals(
            listOf("OFF", "QUICK_TRANSLATE_ONLY", "ALL"),
            onEdt { (0 until combo.itemCount).map { combo.modeAt(it) } }
        )
    }

    @Test
    fun `the saved mode is the one shown`() {
        val store = newStore(Configuration.DEFAULT.copy(autoCopyTranslation = AutoCopyTranslation.QUICK_TRANSLATE_ONLY))
        val panel = onEdt { TranslationPanel(store, localizer) }
        onEdt { panel.render(store.state.value) }
        val combo = autoCopyCombo(panel)

        val selected = onEdt { combo.modeAt(combo.selectedIndex) }
        assertEquals(AutoCopyTranslation.QUICK_TRANSLATE_ONLY.name, selected)
    }

    @Test
    fun `off is what a fresh configuration shows`() {
        val store = newStore()
        val panel = onEdt { TranslationPanel(store, localizer) }
        onEdt { panel.render(store.state.value) }
        val combo = autoCopyCombo(panel)

        val selected = onEdt { combo.modeAt(combo.selectedIndex) }
        assertEquals(AutoCopyTranslation.OFF.name, selected)
    }

    @Test
    fun `choosing a mode writes it to the draft`() {
        val store = newStore()
        val panel = onEdt { TranslationPanel(store, localizer) }
        onEdt { panel.render(store.state.value) }
        val combo = autoCopyCombo(panel)

        val index = onEdt { combo.indexOfMode(AutoCopyTranslation.ALL) }
        onEdt { combo.selectedIndex = index }

        assertEquals(
            AutoCopyTranslation.ALL,
            store.state.value.workingConfiguration.autoCopyTranslation,
            "the selection is a draft change, saved with the rest of the settings"
        )
    }

    @Test
    fun `the hint says that instant translations are left out`() {
        val hint = localizer.getString("settings_translation.auto_copy_hint")
        assertTrue(hint.contains("Instant"), "the hint must mention instant translations: $hint")
    }

    @Test
    fun `every label the setting uses is localized`() {
        for (key in listOf("auto_copy_translation", "auto_copy_off", "auto_copy_quick", "auto_copy_all", "auto_copy_hint")) {
            val value = localizer.getString("settings_translation.$key")
            assertTrue(value.isNotBlank() && !value.startsWith("settings_translation."), "$key is unresolved: $value")
        }
    }
}
