package com.github.ahatem.qtranslate.plugins.systemocr.backend

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PathExecutableLocatorTest {

    private fun tempDir(): File = Files.createTempDirectory("locator-test").toFile()

    @Test
    fun `finds an executable on the unix path`() {
        val directory = tempDir()
        val executable = File(directory, "tesseract").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }

        assertEquals(
            executable.absolutePath,
            PathExecutableLocator.locate("tesseract", directory.absolutePath, null, "Linux"),
        )
    }

    @Test
    fun `resolves an extension on windows using pathext`() {
        val directory = tempDir()
        val executable = File(directory, "powershell.EXE").apply {
            writeText("x")
            setExecutable(true)
        }

        assertEquals(
            executable.absolutePath,
            PathExecutableLocator.locate("powershell", directory.absolutePath, ".COM;.EXE;.BAT", "Windows 11"),
        )
    }

    @Test
    fun `returns null when the executable is absent`() {
        assertNull(PathExecutableLocator.locate("tesseract", tempDir().absolutePath, null, "Linux"))
    }

    @Test
    fun `ignores a directory with the executable name`() {
        val directory = tempDir()
        File(directory, "tesseract").mkdirs()

        assertNull(PathExecutableLocator.locate("tesseract", directory.absolutePath, null, "Linux"))
    }

    @Test
    fun `skips empty path entries`() {
        val directory = tempDir()
        val executable = File(directory, "tesseract").apply { writeText("x"); setExecutable(true) }

        val path = "${File.pathSeparator}${directory.absolutePath}${File.pathSeparator}"

        assertEquals(executable.absolutePath, PathExecutableLocator.locate("tesseract", path, null, "Linux"))
    }
}
