package com.github.ahatem.qtranslate.core.updater

/**
 * Which file the update dialog's Download button should fetch.
 *
 * A release carries the application in three shapes alongside a plugin JAR for every bundled
 * plugin, and the updater used to take whichever asset GitHub listed first. GitHub lists them
 * alphabetically, so first has always meant `ai-plugin-<version>.jar`: pressing Download in a
 * release-shipping application handed the user a single plugin and called it an update. Nothing
 * failed, and nothing said anything was wrong, which is why it survived several releases.
 *
 * Matching is therefore by name and deliberately narrow. There is no "first asset" fallback and
 * there must never be one: an asset this cannot identify is far more likely to be a plugin than
 * the application, so the caller falls back to the release page, where a person can choose.
 */
internal object ReleaseAssets {

    /**
     * The asset name a user on this platform should be given, or `null` if none is recognisable.
     *
     * Windows is offered the packaged build first because it carries its own Java runtime and is
     * the answer for most people there. Everywhere else the portable archive is the equivalent,
     * and the bare application JAR is the last resort on either.
     */
    fun selectDownload(assetNames: List<String>, onWindows: Boolean): String? {
        val preference = if (onWindows) {
            listOf(WINDOWS_PACKAGE, PORTABLE, APP_ONLY)
        } else {
            listOf(PORTABLE, APP_ONLY)
        }
        return preference.firstNotNullOfOrNull { shape ->
            assetNames.firstOrNull { shape.matches(it) }
        }
    }

    /** `QTranslate-1.4.0-windows-x64.zip`, the build with a bundled runtime. */
    private val WINDOWS_PACKAGE = Regex("""QTranslate-\d[^-]*-windows.*\.zip""", RegexOption.IGNORE_CASE)

    /**
     * `QTranslate-1.4.0.zip`, the portable archive.
     *
     * The `[^-]*` is what separates this from the Windows package: both begin `QTranslate-` and
     * end `.zip`, and only the absence of a further hyphenated part tells them apart.
     */
    private val PORTABLE = Regex("""QTranslate-\d[^-]*\.zip""", RegexOption.IGNORE_CASE)

    /** `QTranslate-App-1.4.0.jar`, the application with no plugins. */
    private val APP_ONLY = Regex("""QTranslate-App-\d[^-]*\.jar""", RegexOption.IGNORE_CASE)
}
