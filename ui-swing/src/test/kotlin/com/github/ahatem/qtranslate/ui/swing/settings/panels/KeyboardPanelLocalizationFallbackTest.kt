package com.github.ahatem.qtranslate.ui.swing.settings.panels

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.core.localization.LanguageTomlParser
import com.github.ahatem.qtranslate.core.localization.LocalizationManager
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The command list's new strings are only written in English and `en-GB`. Every other shipped
 * locale has no entry for them, and must therefore show the embedded English text — never the raw
 * key, never an empty label, never a string left with an unsubstituted `%s`.
 *
 * The four keys are the ones the command list's redesign added: the actions column heading, the
 * table's accessible name, and the row actions' name and description.
 */
class KeyboardPanelLocalizationFallbackTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val keys = listOf(
        "settings_hotkeys.column_actions",
        "settings_hotkeys.command_list_accessible_name",
        "settings_hotkeys.row_actions_accessible_name",
        "settings_hotkeys.row_actions_accessible_description",
    )

    /** The repository's shipped locales, which is what a distribution installs. */
    private fun shippedLanguages(): File {
        // Gradle runs module tests from the module directory, but the folder is at the root, and
        // the test may be launched from either. Search upwards rather than assuming one.
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "languages")
            if (File(candidate, "en-GB.toml").isFile) return candidate
            directory = directory.parentFile
        }
        error("the languages folder was not found above ${System.getProperty("user.dir")}")
    }

    private fun shippedLanguageTags(): List<String> =
        shippedLanguages()
            .listFiles { file -> file.extension == "toml" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .sorted()

    private fun localizerFor(tag: String): LocalizationManager {
        val appData = Files.createTempDirectory("qtranslate-kbd-l10n").toFile()
        val install = Files.createTempDirectory("qtranslate-kbd-l10n-install").toFile()
        // The manager looks in the user's folder first and the installation second, so the shipped
        // files are copied into the second one exactly as a distribution would place them.
        File(install, "languages").mkdirs()
        shippedLanguages().listFiles { file -> file.extension == "toml" }
            .orEmpty()
            .forEach { it.copyTo(File(install, "languages/${it.name}"), overwrite = true) }
        val manager = LocalizationManager(appData, LanguageTomlParser(), logger, install)
        runBlocking { manager.loadLanguage(LanguageCode(tag)) }
        return manager
    }

    @Test
    fun `every shipped locale shows text rather than a key for the new strings`() {
        val tags = shippedLanguageTags()
        assertTrue(tags.size >= 15, "expected the shipped locales, found $tags")

        for (tag in tags) {
            val localizer = localizerFor(tag)
            for (key in keys) {
                val text = localizer.getString(key)
                assertNotEquals(key, text, "$tag has no fallback for $key")
                assertTrue(text.isNotBlank(), "$tag renders an empty label for $key")
                assertTrue(
                    !text.startsWith("settings_hotkeys."),
                    "$tag leaks the key path for $key: $text",
                )
            }
        }
    }

    @Test
    fun `the fallback carries the same text as English for missing keys`() {
        val english = localizerFor("en")
        for (tag in shippedLanguageTags()) {
            val localizer = localizerFor(tag)
            for (key in keys) {
                assertEquals(
                    english.getString(key),
                    localizer.getString(key),
                    "$tag should fall back to the English text for $key",
                )
            }
        }
    }

    @Test
    fun `the row actions description substitutes the command even when untranslated`() {
        // The template only exists in English, so the argument must still be formatted into it.
        for (tag in shippedLanguageTags()) {
            val text = localizerFor(tag)
                .getString("settings_hotkeys.row_actions_accessible_description", "Translate")
            assertTrue(
                text.contains("Translate") && !text.contains("%s"),
                "$tag left the placeholder unformatted: $text",
            )
        }
    }
}
