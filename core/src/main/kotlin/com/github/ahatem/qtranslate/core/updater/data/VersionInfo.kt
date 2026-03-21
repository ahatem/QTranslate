package com.github.ahatem.qtranslate.core.updater.data

/**
 * Domain model representing a released version of the application.
 *
 * Produced by [com.github.ahatem.qtranslate.core.updater.Updater] from the
 * GitHub Releases API response. This type is stable — if the release provider
 * changes (e.g. self-hosted), only the data layer changes; callers keep using
 * this model unchanged.
 *
 * @property versionTag  The raw version tag from the release (e.g. `"v1.2.0"` or `"1.2.0"`).
 * @property releaseName The human-readable release title (e.g. `"QTranslate 1.2.0"`).
 * @property releaseNotes The release description in Markdown format, suitable for rendering
 *   in a changelog dialog.
 * @property downloadUrl Direct download URL for the release asset (e.g. the installer JAR),
 *   or `null` if the release has no attached assets.
 */
data class VersionInfo(
    val versionTag: String,
    val releaseName: String,
    val releaseNotes: String,
    val downloadUrl: String?
) {
    /**
     * Returns `true` if this release is strictly newer than [currentVersion].
     *
     * Version strings are compared as semantic versions (MAJOR.MINOR.PATCH).
     * A leading `"v"` prefix is stripped before comparison, so `"v1.2.0"` and
     * `"1.2.0"` are treated identically.
     *
     * Non-numeric components (e.g. pre-release suffixes like `"1.2.0-beta"`) are
     * stripped — only the numeric prefix is compared. This is intentionally
     * conservative: a pre-release tag will not be offered as an update unless its
     * numeric component is strictly greater.
     *
     * Returns `false` if either version string cannot be parsed.
     *
     * Example:
     * ```kotlin
     * VersionInfo(versionTag = "v1.3.0", ...).isNewerThan("1.2.0") // true
     * VersionInfo(versionTag = "v1.2.0", ...).isNewerThan("1.2.0") // false
     * VersionInfo(versionTag = "v1.2.1", ...).isNewerThan("1.2.0") // true
     * ```
     */
    fun isNewerThan(currentVersion: String): Boolean {
        val remote  = parseVersion(versionTag)    ?: return false
        val current = parseVersion(currentVersion) ?: return false
        return remote > current
    }

    private fun parseVersion(raw: String): Version? {
        val cleaned = raw.trimStart('v', 'V')
            .substringBefore('-')   // strip pre-release suffix
            .substringBefore('+')   // strip build metadata
        val parts = cleaned.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        return Version(
            major = parts.getOrElse(0) { 0 },
            minor = parts.getOrElse(1) { 0 },
            patch = parts.getOrElse(2) { 0 }
        )
    }

    private data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    }
}