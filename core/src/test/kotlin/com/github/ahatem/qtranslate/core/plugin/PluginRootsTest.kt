package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.core.testing.SilentLogger
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

/** Reading plugins from the user's folder alongside a bundled one, and which folder a JAR may be removed from. */
class PluginRootsTest {

    private val sandbox = Files.createTempDirectory("qtranslate-plugin-roots").toFile()


    /** Writes a plugin JAR declaring [id], with no implementation, so the loader reports a load failure. */
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

    private fun pluginManifest(id: String) = PluginManifest(
        id = id,
        name = "Test",
        version = "1.0.0",
        author = "t",
        description = "d",
        minApiVersion = "1.0.0"
    )

    /** A plugin state as discovery reports it for a JAR in the distribution's own folder. */
    private fun bundledPlugin(): PluginState {
        val dir = File(sandbox, "install/plugins").apply { mkdirs() }
        return PluginState(
            manifest = pluginManifest("bundled"),
            status = PluginStatus.ENABLED,
            jarPath = writePluginJar(dir, "bundled-plugin.jar", "bundled").absolutePath,
            bundled = true
        )
    }

    private fun userPlugin(): PluginState {
        val dir = File(sandbox, "user/plugins").apply { mkdirs() }
        return PluginState(
            manifest = pluginManifest("extra"),
            status = PluginStatus.ENABLED,
            jarPath = writePluginJar(dir, "extra-plugin.jar", "extra").absolutePath,
            bundled = false
        )
    }

    private fun scan(directory: File): List<com.github.ahatem.qtranslate.core.plugin.registry.PluginError.LoadFailure> {
        val loader = PluginLoader(SilentLogger, sandbox.resolve("cache"))
        loader.loadPluginsFromDirectory(directory)
        return loader.loadFailures
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
    fun `a bundled plugin is not offered for uninstall but stays controllable`() {
        // The rule under test: a bundled plugin can be turned on and off, but must not be offered an
        // uninstall the host cannot honour. Deleting its JAR would leave an installed copy without a
        // component; ignoring the request would bring it back on the next launch.
        val bundled = bundledPlugin()
        val userPlugin = userPlugin()

        assertTrue(bundled.bundled, "A plugin loaded from the bundled folder must be marked bundled")
        assertFalse(bundled.uninstallable, "A bundled plugin must not be uninstallable")
        assertFalse(userPlugin.bundled)
        assertTrue(userPlugin.uninstallable, "A user-installed plugin must remain uninstallable")
    }

    @Test
    fun `only a JAR in the user's own folder is removable`() {
        val userPlugins = File(sandbox, "user/plugins").apply { mkdirs() }
        val bundledPlugins = File(sandbox, "install/plugins").apply { mkdirs() }
        val portable = File(sandbox, "portable/plugins").apply { mkdirs() }
        val elsewhere = File(sandbox, "user/plugins-backup")
            .apply { mkdirs() }
            .let { File(it, "plugin.jar").apply { writeText("x") } }

        // Deleting a bundled JAR would leave an installed copy missing a component, and a folder
        // that merely shares the user folder's name prefix must not match. In a portable
        // distribution one folder serves as both roots, so nothing is read-only.
        assertTrue(
            PluginInstallLocation.isUserInstalled(userPlugins, writePluginJar(userPlugins, "extra-plugin.jar", "extra")),
            "user folder"
        )
        assertFalse(
            PluginInstallLocation.isUserInstalled(userPlugins, writePluginJar(bundledPlugins, "bundled-plugin.jar", "bundled")),
            "bundled folder"
        )
        assertFalse(PluginInstallLocation.isUserInstalled(userPlugins, elsewhere), "similarly named folder")
        assertTrue(
            PluginInstallLocation.isUserInstalled(portable, writePluginJar(portable, "bundled-plugin.jar", "bundled")),
            "portable distribution"
        )
    }
}
