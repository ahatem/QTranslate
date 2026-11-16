package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import com.github.ahatem.qtranslate.ui.swing.shared.theme.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.awt.Component
import java.awt.Container
import java.io.File
import java.nio.file.Files
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Language files are written immediately, so the page has to say so, and deleting the language
 * that is currently showing must leave the interface pointed at something that exists.
 */
class AppearancePanelLanguageManagementTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val directory by lazy { Files.createTempDirectory("qtranslate-appearance-language").toFile() }

    private val localizer by lazy {
        LocalizationManager(File(directory, "languages"), LanguageTomlParser(), logger)
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(): SettingsStore = runBlocking {
        val repository = SettingsRepository(directory, Json { ignoreUnknownKeys = true }, logger)
        repository.updateConfiguration(Configuration.FRESH_INSTALL)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes += scope
        SettingsStore(repository, logger, scope, Configuration.FRESH_INSTALL)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun descendants(root: Container): List<Component> =
        root.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }

    private fun panel(): AppearancePanel {
        val store = store()
        val themeManager = ThemeManager(directory, logger)
        return onEdt {
            AppearancePanel(store, themeManager, localizer, scopes.first(), openEditor = null)
                .also { it.render(store.state.value) }
        }
    }

    @Test
    fun `the page says the translation file actions take effect immediately`() {
        val hint = localizer.getString("settings_appearance.language_files_immediate")
        val shown = descendants(panel()).any { (it as? JLabel)?.text?.contains(hint) == true }
        assertTrue(shown, "a Cancel button that cannot undo file writes has to be disambiguated")
    }

    @Test
    fun `deleting the active language persists the fallback instead of staging it`() {
        val source = File(
            "src/main/kotlin/com/github/ahatem/qtranslate/ui/swing/settings/panels/AppearancePanel.kt"
        ).readText()

        assertTrue(
            source.contains("store.dispatch(SettingsIntent.ToggleSetting { it.copy(interfaceLanguage = \"en\") })"),
            "the file is gone, so the stored interface language must follow immediately",
        )
        assertFalse(
            source.contains("applyDraft(store) { it.copy(interfaceLanguage = \"en\") }"),
            "an unsaved draft would keep a deleted translation selected, or lose the correction on Cancel",
        )
    }
}
