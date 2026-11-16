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
 * The Languages page's new empty-state sentence, in the three locales this batch touches.
 *
 * Arabic was fully translated in the command-list batch; a new key left in English would make the
 * Arabic page switch languages mid-sentence, so its absence is a failure, not a fallback.
 */
class LanguagesPanelLocalizationTest {

    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    private val key = "settings_languages.rules_empty"

    private fun root(): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            if (File(directory, "languages/en-GB.toml").isFile &&
                File(directory, "core/src/main/resources/localization/embedded_en.toml").isFile
            ) return directory
            directory = directory.parentFile
        }
        error("the repository root was not found above ${System.getProperty("user.dir")}")
    }

    private fun resource(path: String): File = File(root(), path)

    private fun textIn(path: String): String {
        val file = resource(path)
        assertTrue(file.isFile, "$path exists")
        val line = file.readLines()
            .firstOrNull { it.trimStart().startsWith("rules_empty") }
        assertTrue(line != null, "$path carries ${key.removePrefix("settings_languages.")}")
        return line.substringAfter('=').trim().trim('"')
    }

    private fun localizerFor(tag: String): LocalizationManager {
        val appData = Files.createTempDirectory("qtranslate-languages-i18n").toFile()
        val install = Files.createTempDirectory("qtranslate-languages-i18n-install").toFile()
        File(install, "languages").mkdirs()
        File(root(), "languages").listFiles { file -> file.extension == "toml" }
            .orEmpty()
            .forEach { it.copyTo(File(install, "languages/${it.name}"), overwrite = true) }
        val manager = LocalizationManager(appData, LanguageTomlParser(), logger, install)
        runBlocking { manager.loadLanguage(LanguageCode(tag)) }
        return manager
    }

    @Test
    fun `the empty-state sentence exists in english, british english and arabic`() {
        val english = textIn("core/src/main/resources/localization/embedded_en.toml")
        val british = textIn("languages/en-GB.toml")
        val arabic = textIn("languages/ar-SA.toml")

        assertTrue(english.isNotBlank(), "the embedded English sentence is real text")
        assertEquals(english, british, "en-GB ships the same wording as the embedded source")
        assertTrue(arabic.any { it in '\u0600'..'\u06FF' }, "ar-SA carries Arabic, not the English sentence")
        assertNotEquals(english, arabic, "ar-SA is a translation, not a fallback")
    }

    @Test
    fun `the loaded managers agree with the files and keep any placeholder`() {
        val english = localizerFor("en-GB")
        val arabic = localizerFor("ar-SA")

        assertEquals(textIn("languages/en-GB.toml"), english.getString(key))
        assertEquals(textIn("languages/ar-SA.toml"), arabic.getString(key))

        val placeholders = Regex("%[sd]")
        assertEquals(
            placeholders.findAll(english.getString(key)).map { it.value }.toList(),
            placeholders.findAll(arabic.getString(key)).map { it.value }.toList(),
            "a translation keeps the placeholders the code fills in",
        )
    }
}
