package com.github.ahatem.qtranslate.core.updater.data

data class VersionInfo(
    val versionTag: String,
    val releaseName: String,
    val releaseNotes: String, // format will be in Markdown/HTML
    val downloadUrl: String?
)