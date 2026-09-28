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
 * @property releaseUrl URL to the GitHub release page, suitable for a "View on GitHub" button.
 */
data class VersionInfo(
    val versionTag: String,
    val releaseName: String,
    val releaseNotes: String,
    val downloadUrl: String?,
    val releaseUrl: String? = null
) {
    /**
     * Returns `true` if this release is strictly newer than [currentVersion].
     *
     * Uses SemVer precedence, ignoring build metadata. A leading `v` is accepted
     * for GitHub tags. A stable version outranks prereleases of the same base.
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
        val match = SEMVER.matchEntire(raw.removePrefix("v").removePrefix("V")) ?: return null
        val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.')
        if (prerelease?.any { it.length > 1 && it[0] == '0' && it.all(Char::isDigit) } == true) return null
        return Version(
            major = match.groupValues[1].toIntOrNull() ?: return null,
            minor = match.groupValues[2].toIntOrNull() ?: return null,
            patch = match.groupValues[3].toIntOrNull() ?: return null,
            prerelease = prerelease
        )
    }

    private companion object {
        val SEMVER = Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
    }

    private data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val prerelease: List<String>?
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            val base = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
            if (base != 0) return base
            if (prerelease == null) return if (other.prerelease == null) 0 else 1
            if (other.prerelease == null) return -1
            for ((left, right) in prerelease.zip(other.prerelease)) {
                val leftNumber = left.all(Char::isDigit)
                val rightNumber = right.all(Char::isDigit)
                val order = when {
                    leftNumber && rightNumber -> compareValues(left.length, right.length).takeIf { it != 0 }
                        ?: left.compareTo(right)
                    leftNumber -> -1
                    rightNumber -> 1
                    else -> left.compareTo(right)
                }
                if (order != 0) return order
            }
            return prerelease.size.compareTo(other.prerelease.size)
        }
    }
}
