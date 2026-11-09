package com.github.ahatem.qtranslate.core.plugin

import java.io.File

/** Inert metadata read from an application-owned copy before provider code is loaded. */
internal data class PluginAssessment(
    val manifest: PluginManifest,
    val jarFile: File,
    val snapshotFile: File,
    val jarHash: String,
    val providerClassName: String
) {
    fun discard() { snapshotFile.setWritable(true); snapshotFile.delete() }
}
