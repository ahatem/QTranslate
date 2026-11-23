package com.github.ahatem.qtranslate.ui.swing.shared.theme

import com.github.ahatem.qtranslate.ui.swing.testing.SilentLogger
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Theme discovery when the bundled distribution and the user folder are separate roots. */
class ThemeManagerRootTest {

    private val sandbox = Files.createTempDirectory("qtranslate-theme-roots").toFile()
    private val userRoot = File(sandbox, "user-data")
    private val installRoot = File(sandbox, "install")

    /**
     * A minimal IntelliJ theme file. [ThemeManager] parses it with IntelliJTheme to learn the name and
     * whether it is dark, so the properties it reads have to be present.
     */
    private fun writeTheme(directory: File, fileName: String, themeName: String, dark: Boolean): File {
        directory.mkdirs()
        return File(directory, fileName).apply {
            writeText(
                """
                {"editorScheme":"$fileName","name":"$themeName","dark":$dark,
                 "colors":{"editor.background":"#ffffff","editor.foreground":"#000000"}}
                """.trimIndent()
            )
        }
    }

    private fun manager(installationRoot: File?): ThemeManager =
        ThemeManager(userRoot, SilentLogger, installationRoot)

    private fun idOf(fileName: String): String =
        "external:${fileName.removeSuffix(".json").lowercase().replace(" ", "_")}"

    @Test
    fun `themes from the installation and the user folder are both discovered`() {
        writeTheme(File(installRoot, "themes"), "from_distribution.theme.json", "From Distribution", true)
        writeTheme(File(userRoot, "themes"), "from_user.theme.json", "From User", false)

        val ids = manager(installRoot).getAvailableThemes().map { it.id }

        assertTrue(idOf("from_distribution.theme.json") in ids, "installation-only theme")
        assertTrue(idOf("from_user.theme.json") in ids, "user-only theme")
    }

    @Test
    fun `a user theme overrides a bundled theme of the same name, whichever is darker`() {
        // The folders are scanned user-first; an unconditional map insert let the later bundled one win.
        for (userIsDark in listOf(true, false)) {
            val label = if (userIsDark) "user is the darker" else "user is the lighter"
            writeTheme(File(installRoot, "themes"), "shared.theme.json", "Bundled", !userIsDark)
            writeTheme(File(userRoot, "themes"), "shared.theme.json", "User", userIsDark)

            val themes = manager(installRoot).getAvailableThemes()
            val resolved = themes.single { it.id == idOf("shared.theme.json") }

            assertEquals("User", resolved.name, label)
            assertEquals(userIsDark, resolved.isDark, label)
            assertTrue(userRoot.resolve("themes/shared.theme.json").isFile, "$label: the user's file is left alone")
        }
    }

    @Test
    fun `a single root behaves as before`() {
        writeTheme(File(userRoot, "themes"), "only.theme.json", "Only", true)

        val themes = ThemeManager(userRoot, SilentLogger).getAvailableThemes()

        assertTrue(themes.any { it.id == idOf("only.theme.json") })
    }
}
