package com.github.ahatem.qtranslate.ui.swing.shared.theme

import com.github.ahatem.qtranslate.api.core.Logger
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers theme discovery when the bundled distribution and the user's own folder are separate.
 *
 * Themes ship as loose theme.json files under `themes/`, so an installed copy reads its bundled ones
 * from the installation while a user's additions live in their own data folder. The two can carry the
 * same filename, and then the user's must win.
 */
class ThemeManagerRootTest {

    private val sandbox = Files.createTempDirectory("qtranslate-theme-roots").toFile()
    private val userRoot = File(sandbox, "user-data")
    private val installRoot = File(sandbox, "install")

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

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
        ThemeManager(userRoot, silentLogger, installationRoot)

    private fun idOf(fileName: String): String =
        "external:${fileName.removeSuffix(".json").lowercase().replace(" ", "_")}"

    @Test
    fun `a user theme overrides a bundled theme with the same external id`() {
        // The defect this covers: the folders are scanned user-first, but an unconditional map insert
        // let the bundled theme — scanned second — overwrite the user's.
        val userTheme = writeTheme(File(userRoot, "themes"), "mine.theme.json", "Mine", true)
        writeTheme(File(installRoot, "themes"), "mine.theme.json", "Bundled", false)

        val themes = manager(installRoot).getAvailableThemes()
        val resolved = themes.single { it.id == idOf("mine.theme.json") }

        assertEquals(1, themes.count { it.id == idOf("mine.theme.json") }, "The id must appear once")
        assertEquals("Mine", resolved.name, "The user's theme must win over the bundled one of the same name")
    }

    @Test
    fun `a bundled-only theme is still discovered`() {
        writeTheme(File(installRoot, "themes"), "bundled_only.theme.json", "Bundled Only", true)

        val themes = manager(installRoot).getAvailableThemes()

        assertTrue(
            themes.any { it.id == idOf("bundled_only.theme.json") },
            "A theme shipped with the installation must be found when it is in a separate root"
        )
    }

    @Test
    fun `a user-only theme is still discovered`() {
        writeTheme(File(userRoot, "themes"), "mine_only.theme.json", "Mine Only", true)

        val themes = manager(installRoot).getAvailableThemes()

        assertTrue(themes.any { it.id == idOf("mine_only.theme.json") })
    }

    @Test
    fun `bundled and user themes with different names both appear`() {
        writeTheme(File(installRoot, "themes"), "from_distribution.theme.json", "From Distribution", true)
        writeTheme(File(userRoot, "themes"), "from_user.theme.json", "From User", false)

        val ids = manager(installRoot).getAvailableThemes().map { it.id }

        assertTrue(idOf("from_distribution.theme.json") in ids)
        assertTrue(idOf("from_user.theme.json") in ids)
    }

    @Test
    fun `a user theme wins whichever side declares the darker variant`() {
        // The bundled copy is the lighter of the two here, so a regression that simply preferred the
        // first-seen entry would pass the previous test and fail this one.
        writeTheme(File(installRoot, "themes"), "shared.theme.json", "Bundled", false)
        writeTheme(File(userRoot, "themes"), "shared.theme.json", "User", true)

        val resolved = manager(installRoot).getAvailableThemes()
            .single { it.id == idOf("shared.theme.json") }

        assertEquals("User", resolved.name)
        assertTrue(resolved.isDark)
        assertTrue(userRoot.resolve("themes/shared.theme.json").isFile, "The user's file must be left alone")
    }

    @Test
    fun `a single root behaves as before`() {
        // Portable distributions pass no installation root; everything is then one folder.
        writeTheme(File(userRoot, "themes"), "only.theme.json", "Only", true)

        val themes = ThemeManager(userRoot, silentLogger).getAvailableThemes()

        assertTrue(themes.any { it.id == idOf("only.theme.json") })
    }
}