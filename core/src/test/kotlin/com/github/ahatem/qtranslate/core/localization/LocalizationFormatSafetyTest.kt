package com.github.ahatem.qtranslate.core.localization

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.api.language.LanguageCode
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A badly formatted translation degrades instead of throwing. Strings come from files anyone can
 * write, and one dropped into `languages/` by hand never passes the editor's placeholder check.
 */
class LocalizationFormatSafetyTest {

    /** A real key that takes one argument, so the test breaks if the English file changes shape. */
    private val key = "settings_appearance.delete_confirm"

    @Test
    fun `a translation that cannot be formatted falls back to English rather than throwing`() = runTest {
        val broken = listOf(
            "extra placeholder" to "Supprimer la traduction %s de %s ?",
            // Ordinary prose, not a placeholder mistake; the editor's check does not flag it.
            "percent sign in prose" to "Supprimer %s (100%) ?",
            "changed conversion type" to "Supprimer %d ?",
        )

        for ((label, translation) in broken) {
            val (manager, _) = managerWith(translation)

            assertEquals(manager.englishStrings().getValue(key).format("fr-FR"), manager.getString(key, "fr-FR"), label)
        }
    }

    @Test
    fun `a correct translation is still used`() {
        val (manager, _) = managerWith("Supprimer la traduction %s ?")

        assertEquals("Supprimer la traduction fr-FR ?", manager.getString(key, "fr-FR"))
    }

    @Test
    fun `dropping a placeholder is left alone, because format accepts it`() {
        // String.format ignores extra arguments, so this never threw and must stay as written.
        val (manager, _) = managerWith("Supprimer cette traduction ?")

        assertEquals("Supprimer cette traduction ?", manager.getString(key, "fr-FR"))
    }

    @Test
    fun `a broken string is reported once, not on every repaint`() {
        val (manager, warnings) = managerWith("Supprimer %s de %s ?")

        repeat(20) { manager.getString(key, "fr-FR") }

        assertEquals(1, warnings.count { key in it }, "Expected exactly one warning, got: $warnings")
    }

    @Test
    fun `no arguments means no formatting, so a lone percent is safe`() {
        // Nothing to substitute, so the string is handed back untouched.
        val (manager, _) = managerWith("Progression : 100%")

        assertEquals("Progression : 100%", manager.getString(key))
    }

    // -------------------------------------------------------------------------

    /** A manager whose active language holds [translation] for [key], plus the warnings it logs. */
    private fun managerWith(translation: String): Pair<LocalizationManager, List<String>> {
        val dir = createTempDirectory("qtranslate-loc").toFile().apply { deleteOnExit() }
        File(dir, "languages").apply { mkdirs() }
            .resolve("fr-FR.toml")
            .writeText(
                """
                [meta]
                name = "French"

                [settings_appearance]
                ${key.substringAfter('.')} = "$translation"
                """.trimIndent()
            )

        val warnings = mutableListOf<String>()
        val logger = object : Logger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) { warnings += message }
            override fun error(message: String, error: Throwable?) = Unit
        }

        val manager = LocalizationManager(dir, LanguageTomlParser(), logger)
        kotlinx.coroutines.runBlocking { manager.loadLanguage(LanguageCode("fr-FR")) }

        assertTrue(
            manager.englishStrings().containsKey(key),
            "The English file no longer has '$key'; this test needs a key that takes an argument"
        )
        return manager to warnings
    }
}
