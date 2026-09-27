package com.github.ahatem.qtranslate.plugins.systemservices

import com.github.ahatem.qtranslate.plugins.systemservices.backend.MAC_VISION_HELPER_RESOURCE
import com.github.ahatem.qtranslate.plugins.systemservices.spell.SystemSpellCheckerBackends
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Inspects the JAR this build produced rather than the source tree, since packaging mistakes live in
 * the gap between the two.
 *
 * The Gradle test task passes the JAR path in `systemServices.pluginJar` and whether a macOS helper was
 * available to the build in `systemServices.macHelperPackaged`. Run outside Gradle, both are absent and
 * the checks skip.
 */
class PackagedPluginJarTest {

    private val jarPath: String? = System.getProperty("systemServices.pluginJar")
    private val macHelperPackaged: Boolean =
        System.getProperty("systemServices.macHelperPackaged")?.toBoolean() ?: false
    private val macSpellPackaged: Boolean =
        System.getProperty("systemServices.macSpellPackaged")?.toBoolean() ?: false

    private val entries: Set<String> by lazy { readEntries() }

    private fun readEntries(): Set<String> {
        val file = jarPath?.let(::File) ?: return emptySet()
        if (!file.isFile) return emptySet()
        return ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
    }

    private fun skipIfNoJar(): Set<String> {
        val entries = entries
        if (entries.isEmpty()) {
            println("PackagedPluginJarTest skipped: no packaged JAR was supplied by the build.")
        }
        return entries
    }

    @Test
    fun `the packaged jar carries the manifest, icon, and service registration`() {
        val entries = skipIfNoJar()
        if (entries.isEmpty()) return

        assertTrue("plugin.json" in entries, "plugin.json is missing from the packaged jar")
        assertTrue("assets/system-services-icon.svg" in entries, "the plugin icon is missing")
        assertTrue("assets/system-ocr-icon.svg" in entries, "the OCR icon is missing")
        assertTrue("assets/system-tts-icon.svg" in entries, "the TTS icon is missing")
        assertTrue("assets/system-spell-icon.svg" in entries, "the spell checker icon is missing")
        assertTrue(PLUGIN_SERVICE_ENTRY in entries, "the ServiceLoader registration is missing")
    }

    @Test
    fun `the service registration names the plugin the loader will instantiate`() {
        val entries = skipIfNoJar()
        if (entries.isEmpty()) return

        assertEquals(
            "com.github.ahatem.qtranslate.plugins.systemservices.SystemServicesPlugin",
            readEntry(PLUGIN_SERVICE_ENTRY),
        )
    }

    @Test
    fun `the packaged jar carries all platform helper scripts`() {
        val entries = skipIfNoJar()
        if (entries.isEmpty()) return

        assertTrue("scripts/windows-ocr.ps1" in entries, "the Windows OCR helper script is missing")
        assertTrue("scripts/windows-tts.ps1" in entries, "the Windows TTS helper script is missing")
        assertTrue("scripts/vision_ocr.swift" in entries, "the Vision helper source is missing")
        assertTrue("scripts/system_spell.swift" in entries, "the spell helper source is missing")
    }

    @Test
    fun `the macOS helper is packaged at exactly the path the runtime looks up`() {
        val entries = skipIfNoJar()
        if (entries.isEmpty()) return

        if (macHelperPackaged) {
            assertTrue(
                MAC_VISION_HELPER_RESOURCE in entries,
                "expected the Vision helper at '$MAC_VISION_HELPER_RESOURCE', but the jar contains " +
                    entries.filter { it.contains("vision_ocr") },
            )
        } else {
            assertFalse(
                MAC_VISION_HELPER_RESOURCE in entries,
                "this build had no Swift toolchain, so it must not package a Vision helper",
            )
        }
    }

    @Test
    fun `the macOS helper is never nested under a duplicated path`() {
        val entries = skipIfNoJar()
        if (entries.isEmpty()) return

        // Guards against the helper being staged under a doubled prefix the runtime cannot find.
        val nested = entries.filter { it.contains("native/macos/native/macos") }
        assertTrue(nested.isEmpty(), "the Vision helper was nested at a duplicated path: $nested")
    }

    @Test fun `the macOS spell helper packaging matches runtime lookup`() {
        val entries = skipIfNoJar()
        if (entries.isEmpty()) return
        if (macSpellPackaged) assertTrue(SystemSpellCheckerBackends.MAC_RESOURCE in entries)
        else assertFalse(SystemSpellCheckerBackends.MAC_RESOURCE in entries)
    }

    private fun readEntry(name: String): String {
        val file = File(jarPath!!)
        return ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry(name)).use { it.readBytes().toString(Charsets.UTF_8) } }
            .trim()
    }

    private companion object {
        const val PLUGIN_SERVICE_ENTRY = "META-INF/services/com.github.ahatem.qtranslate.api.plugin.Plugin"
    }
}
