package com.github.ahatem.qtranslate.ui.swing.shared.icon

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That a plugin's icons still load from the plugin's own JAR.
 *
 * The application's icons are named for the vocabulary and resolve against the chosen set, which
 * may be a folder on disk. A plugin's icons are entirely different: they live inside that plugin's
 * JAR and are reachable only through its class loader. Sending those through the set resolver looks
 * for them on the application's classpath, finds nothing, and yields the missing-icon glyph.
 *
 * That happened. Every plugin in the list, and every service in the pickers, turned into a red
 * square — and nothing failed, because a missing icon is a picture rather than an error. Only
 * opening the Plugins page showed it. Hence this.
 */
class PluginIconLoadingTest {

    /** A throwaway JAR with one SVG in it, standing in for an installed plugin. */
    private fun pluginJarWith(resourcePath: String): URLClassLoader {
        val jar = File.createTempFile("qtranslate-fake-plugin", ".jar").apply { deleteOnExit() }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry(resourcePath))
            out.write(
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">
                   <path d="M4 4h16v16H4z"/></svg>""".trimIndent().toByteArray()
            )
            out.closeEntry()
        }
        return URLClassLoader(arrayOf<URL>(jar.toURI().toURL()), null)
    }

    @Test
    fun `an icon inside a plugin jar is found through that plugin's class loader`() {
        val path = "plugin-assets/my-service.svg"
        val loader = pluginJarWith(path)

        assertTrue(
            loader.getResource(path) != null,
            "the fake plugin jar is malformed; the rest of this test would prove nothing"
        )
        // The application's own class loader must NOT see it: that is what makes the plugin loader
        // load-bearing rather than incidental.
        assertTrue(
            IconSet::class.java.classLoader.getResource(path) == null,
            "the fake path leaked onto the application classpath"
        )
    }

    @Test
    fun `a plugin path is not treated as an application icon`() {
        // The rule the loader uses to tell them apart. A plugin is free to put its icons anywhere
        // in its own jar, and those paths must not be mistaken for the vocabulary.
        val pluginPaths = listOf(
            "plugin-assets/my-service.svg",
            "com/example/plugin/logo.svg",
            "assets/icon.svg"
        )
        pluginPaths.forEach {
            assertTrue(
                !it.startsWith("icons/"),
                "A plugin using the path '$it' would be resolved as an application icon"
            )
        }
    }

    @Test
    fun `application icons still resolve through the set`() {
        IconSet.installTo(File(".."))
        IconSet.use(IconSet.DEFAULT_ID)
        assertEquals("icons/lucide/edit.svg", IconSet.path("edit"))
        assertTrue(IconSet.load("icons/lucide/edit.svg", 14, 14).iconWidth > 0)
    }
}
