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
 * That a badly formatted translation degrades instead of throwing.
 *
 * `getString` finishes by running the string through `format`, and the strings come from files
 * anyone can write. The language editor warns about a placeholder mismatch but deliberately does
 * not block one, and a file dropped into `languages/` by hand never passes the editor at all. So
 * the application has to survive a string it cannot format, because it will meet one.
 *
 * The percent case is the one worth keeping in mind: it is not a mistake with placeholders at all,
 * it is a translator writing "(100%)" in ordinary prose. The editor's mismatch check does not even
 * flag it, because its pattern needs a letter after the `%`.
 */
class LocalizationFormatSafetyTest {

    /** A real key that takes one argument, so the test breaks if the English file changes shape. */
    private val key = "settings_appearance.delete_confirm"

    @Test
    fun `a translation with an extra placeholder falls back to English rather than throwing`() = runTest {
        val (manager, _) = managerWith("Supprimer la traduction %s de %s ?")

        val result = manager.getString(key, "fr-FR")

        assertEquals(manager.englishStrings().getValue(key).format("fr-FR"), result)
    }

    @Test
    fun `an ordinary percent sign in prose does not throw`() = runTest {
        // Unflagged by the editor and fatal at runtime: the case this whole guard exists for.
        val (manager, _) = managerWith("Supprimer %s (100%) ?")

        val result = manager.getString(key, "fr-FR")

        assertEquals(manager.englishStrings().getValue(key).format("fr-FR"), result)
    }

    @Test
    fun `a translation that changes the conversion type falls back`() = runTest {
        val (manager, _) = managerWith("Supprimer %d ?")

        val result = manager.getString(key, "fr-FR")

        assertEquals(manager.englishStrings().getValue(key).format("fr-FR"), result)
    }

    @Test
    fun `a correct translation is still used`() {
        // The guard must not quietly swallow good translations along with bad ones.
        val (manager, _) = managerWith("Supprimer la traduction %s ?")

        assertEquals("Supprimer la traduction fr-FR ?", manager.getString(key, "fr-FR"))
    }

    @Test
    fun `dropping a placeholder is left alone, because format accepts it`() {
        // Extra arguments are ignored by String.format, so this never threw and must not start
        // being rewritten to English now.
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
        // Nothing to substitute, so the string is handed back untouched. Formatting it anyway
        // would break strings that were never meant to be formatted.
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
