package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import java.nio.file.Files
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginLoaderTest {
    @Test
    fun `directory scan retains malformed jar failure for the plugin manager`() {
        val directory = Files.createTempDirectory("qtranslate-plugin-loader").toFile()
        try {
            val jar = directory.resolve("broken-plugin.jar")
            JarOutputStream(jar.outputStream()).use { }
            val loader = PluginLoader(NoOpLogger)

            val plugins = loader.loadPluginsFromDirectory(directory)

            assertTrue(plugins.isEmpty())
            assertEquals(1, loader.loadFailures.size)
            assertEquals(jar.absolutePath, loader.loadFailures.single().jarPath)
            assertTrue(loader.loadFailures.single().message.contains("Plugin implementation"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private object NoOpLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
}
