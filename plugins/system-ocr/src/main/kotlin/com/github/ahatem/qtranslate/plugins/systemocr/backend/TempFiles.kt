package com.github.ahatem.qtranslate.plugins.systemocr.backend

import java.io.File
import java.nio.file.Files

/**
 * Throwaway files used to hand an image to a helper process and to receive its result. They are
 * deleted once the call finishes.
 */
internal object TempFiles {

    fun create(prefix: String, suffix: String): File =
        Files.createTempFile(prefix, suffix).toFile().also { it.deleteOnExit() }

    fun write(prefix: String, suffix: String, bytes: ByteArray): File =
        create(prefix, suffix).also { it.writeBytes(bytes) }

    fun deleteQuietly(vararg files: File?) {
        for (file in files) {
            if (file == null) continue
            try {
                Files.deleteIfExists(file.toPath())
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }
}
