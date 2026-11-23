package com.github.ahatem.qtranslate.ui.swing.shared.icon

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.github.ahatem.qtranslate.ui.swing.shared.TestIcons
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A plugin's icons live in its own JAR and load through its class loader, not the icon-set resolver. */
class PluginIconLoadingTest {

    @Test
    fun `a plugin icon loads through its own class loader while application icons resolve through the set`() {
        val path = "plugin-assets/my-service.svg"
        val jar = File.createTempFile("qtranslate-fake-plugin", ".jar").apply { deleteOnExit() }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry(path))
            out.write(
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">
                   <path d="M4 4h16v16H4z"/></svg>""".trimIndent().toByteArray()
            )
            out.closeEntry()
        }
        val pluginLoader = URLClassLoader(arrayOf<URL>(jar.toURI().toURL()), null)
        val appLoader = IconManager::class.java.classLoader

        assertTrue(FlatSVGIcon(path, 14, 14, pluginLoader).hasFound(), "the plugin loader finds its own icon")
        assertFalse(FlatSVGIcon(path, 14, 14, appLoader).hasFound(), "the application classpath does not")

        IconSet.installTo(File(".."))
        IconSet.use(IconSet.DEFAULT_ID)
        assertTrue(TestIcons.iconManager().getIcon("icons/lucide/edit.svg", 14, 14).iconWidth > 0)
    }
}
