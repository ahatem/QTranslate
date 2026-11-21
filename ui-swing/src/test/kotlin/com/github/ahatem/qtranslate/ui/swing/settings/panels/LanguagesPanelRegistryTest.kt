package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.settings.mvi.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pinned languages and translation rules answer to the active translator's language list, not to
 * a hard-coded catalogue. A plugin's own language can be pinned or ruled, and a stored choice the
 * current translator does not advertise stays visible so it is not silently deleted on save.
 */
class LanguagesPanelRegistryTest {

    private val scopes = mutableListOf<CoroutineScope>()

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val localizer by lazy {
        LocalizationManager(
            Files.createTempDirectory("qtranslate-languages-registry").toFile(),
            LanguageTomlParser(),
            logger,
        )
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(config: Configuration): SettingsStore = runBlocking {
        val directory = Files.createTempDirectory("qtranslate-languages-registry-store").toFile()
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

    private val advertised = listOf("auto", "en", "fr", "ja").map(::LanguageCode)

    private fun panel(config: Configuration): LanguagesPanel {
        val store = store(config)
        return onEdt {
            LanguagesPanel(store, localizer) { advertised }.also { it.render(store.state.value) }
        }
    }

    @Test
    fun `the pin list offers what the translator advertises`() {
        val panel = panel(Configuration.FRESH_INSTALL)

        assertEquals(
            listOf("en", "fr", "ja"),
            panel.pinnedCodesForTest(),
            "Auto is never a pinned target, and a language the translator lacks is not invented",
        )
    }

    @Test
    fun `a pinned language the translator lacks stays visible`() {
        val panel = panel(Configuration.FRESH_INSTALL.copy(pinnedLanguages = listOf("fr", "de")))

        assertEquals(
            listOf("en", "fr", "ja", "de"),
            panel.pinnedCodesForTest(),
            "the stored pin is shown rather than silently dropped",
        )
        // The reason is a tooltip on the tile's name now: the tile is a grid cell, so the sentence
        // that used to trail the checkbox label would not fit in it.
        val unavailableReason = localizer.getString("settings_languages.pin_unavailable")
        val (tags, tooltips) = onEdt {
            val view = panel.pinnedListForTest().viewport.view as java.awt.Container
            val tags = mutableListOf<String>()
            val tooltips = mutableListOf<String?>()
            fun walk(container: java.awt.Container) {
                for (index in 0 until container.componentCount) {
                    val child = container.getComponent(index)
                    if (child is javax.swing.JLabel) {
                        tags += child.text.orEmpty()
                        tooltips += child.toolTipText
                    }
                    if (child is java.awt.Container) walk(child)
                }
            }
            walk(view)
            tags to tooltips
        }
        assertTrue(tags.contains("de"), "the unavailable language's tile is still shown")
        assertTrue(
            tooltips.contains(unavailableReason),
            "and it says why it is not offered by the active translator",
        )
    }

    @Test
    fun `the pin list follows a translator change on render`() {
        val config = Configuration.FRESH_INSTALL
        val store = store(config)
        var languages: List<LanguageCode> = listOf(LanguageCode("en"), LanguageCode("fr"))
        val panel = onEdt {
            LanguagesPanel(store, localizer) { languages }.also { it.render(store.state.value) }
        }
        assertEquals(listOf("en", "fr"), panel.pinnedCodesForTest())

        languages = listOf(LanguageCode("en"), LanguageCode("de"))
        onEdt { panel.render(store.state.value) }

        assertEquals(
            listOf("en", "de"),
            panel.pinnedCodesForTest(),
            "switching translator changes what can be pinned instead of leaving a stale list",
        )
    }
}
