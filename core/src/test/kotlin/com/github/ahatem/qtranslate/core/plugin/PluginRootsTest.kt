package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.plugin.installer.PluginInstallLocation
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers reading plugins from the user's folder alongside a separate bundled one.
 *
 * Fixtures are real JARs carrying a manifest. Each is rejected on its missing implementation rather
 * than on its manifest, which is what proves the folder was read and the manifest understood.
 */
class PluginRootsTest {

    private val sandbox = Files.createTempDirectory("qtranslate-plugin-roots").toFile()

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    /**
     * Writes a plugin JAR declaring [id].
     *
     * There is deliberately no implementation or service entry, so the loader reports a load failure.
     * That is enough: the question here is which folders are read and in what order, not whether a
     * plugin runs.
     */
    private fun writePluginJar(directory: File, fileName: String, id: String): File {
        directory.mkdirs()
        val jar = File(directory, fileName)
        JarOutputStream(jar.outputStream(), Manifest()).use { out ->
            out.putNextEntry(JarEntry("plugin.json"))
            out.write(
                """{"id":"$id","name":"Test","version":"1.0.0","author":"t",
                   "description":"d","minApiVersion":"1.0.0"}""".toByteArray()
            )
            out.closeEntry()
        }
        return jar
    }

    private fun scan(directory: File): List<com.github.ahatem.qtranslate.core.plugin.registry.PluginError.LoadFailure> {
        val loader = PluginLoader(silentLogger)
        loader.loadPluginsFromDirectory(directory)
        return loader.loadFailures
    }

    @Test
    fun `a JAR missing its implementation is reported against its own folder`() {
        val userPlugins = File(sandbox, "user/plugins").apply { mkdirs() }
        val jar = writePluginJar(userPlugins, "extra-plugin.jar", "extra")

        val failures = scan(userPlugins)

        assertEquals(1, failures.size)
        assertTrue(failures.single().message.contains("Plugin implementation"), failures.single().message)
        assertEquals(jar.absolutePath, failures.single().jarPath)
    }

    @Test
    fun `both the user's and the bundled folder are read`() {
        val userPlugins = File(sandbox, "user/plugins").apply { mkdirs() }
        val bundledPlugins = File(sandbox, "install/plugins").apply { mkdirs() }
        writePluginJar(userPlugins, "extra-plugin.jar", "extra")
        writePluginJar(bundledPlugins, "bundled-plugin.jar", "bundled")

        assertEquals(1, scan(userPlugins).size)
        assertEquals(1, scan(bundledPlugins).size)
    }

    @Test
    fun `a missing plugins folder yields nothing instead of throwing`() {
        assertTrue(scan(File(sandbox, "does-not-exist")).isEmpty())
    }

    @Test
    fun `an unreadable JAR is reported with its path`() {
        val userPlugins = File(sandbox, "user/plugins").apply { mkdirs() }
        val broken = File(userPlugins, "broken-plugin.jar").apply { writeText("not a jar") }

        val failures = scan(userPlugins)

        assertEquals(1, failures.size)
        assertEquals(broken.absolutePath, failures.single().jarPath)
    }

    // ── Which folder a JAR may be removed from ───────────────────────────────

    @Test
    fun `a JAR in the user's own folder is removable`() {
        val userPlugins = File(sandbox, "user/plugins").apply { mkdirs() }
        val jar = writePluginJar(userPlugins, "extra-plugin.jar", "extra")

        assertTrue(PluginInstallLocation.isUserInstalled(userPlugins, jar))
    }

    @Test
    fun `a bundled JAR is not removable`() {
        // Uninstalling a bundled plugin used to delete the shipped file, leaving an installed copy
        // missing a component with no way back short of reinstalling.
        val userPlugins = File(sandbox, "user/plugins").apply { mkdirs() }
        val bundledPlugins = File(sandbox, "install/plugins").apply { mkdirs() }
        val bundled = writePluginJar(bundledPlugins, "bundled-plugin.jar", "bundled")

        assertFalse(PluginInstallLocation.isUserInstalled(userPlugins, bundled))
    }

    @Test
    fun `a similarly named folder elsewhere is not mistaken for the user's own`() {
        val userPlugins = File(sandbox, "user/plugins").apply { mkdirs() }
        val elsewhere = File(sandbox, "user/plugins-backup")
            .apply { mkdirs() }
            .let { File(it, "plugin.jar").apply { writeText("x") } }

        assertFalse(PluginInstallLocation.isUserInstalled(userPlugins, elsewhere))
    }

    @Test
    fun `a portable distribution treats every JAR as the user's own`() {
        // One folder serves as both roots, which is the portable contract: nothing is read-only.
        val plugins = File(sandbox, "portable/plugins").apply { mkdirs() }
        val jar = writePluginJar(plugins, "bundled-plugin.jar", "bundled")

        assertTrue(PluginInstallLocation.isUserInstalled(plugins, jar))
    }
}