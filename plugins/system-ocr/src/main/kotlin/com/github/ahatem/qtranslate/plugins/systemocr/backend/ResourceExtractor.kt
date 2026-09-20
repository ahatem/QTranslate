package com.github.ahatem.qtranslate.plugins.systemocr.backend

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Stages files that live inside the plugin JAR, such as the PowerShell script and the compiled
 * macOS helper, into the plugin's data directory so they can be executed. The target is rewritten on
 * every enable, so an updated JAR never runs a stale copy.
 */
internal object ResourceExtractor {

    /** The staged file, or null when the resource is absent from the JAR. */
    fun extract(resourcePath: String, target: File, executable: Boolean = false): File? {
        val loader = ResourceExtractor::class.java.classLoader ?: return null
        val bytes = loader.getResourceAsStream(resourcePath)?.use { it.readBytes() } ?: return null
        return writeAtomically(target, bytes, executable)
    }

    private fun writeAtomically(target: File, bytes: ByteArray, executable: Boolean): File? = try {
        target.parentFile?.mkdirs()
        val staging = File.createTempFile("${target.name}-", ".staging", target.parentFile)
        staging.writeBytes(bytes)
        staging.setExecutable(executable, false)
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        target.setExecutable(executable, false)
        target
    } catch (_: IOException) {
        null
    }
}
