package com.github.ahatem.qtranslate.core.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That Download offers the application rather than whatever GitHub happens to list first.
 *
 * The updater took `assets.firstOrNull()`. GitHub returns assets alphabetically, and every release
 * ships a plugin JAR per bundled plugin, so first has always resolved to `ai-plugin-<version>.jar`.
 * Pressing Download downloaded one plugin and presented it as the new version. Nothing threw and
 * nothing logged, which is how it survived releases: it takes someone opening the file to notice.
 *
 * The lists below are the real asset names from v1.3.0 and v1.4.0, taken from the API, so this
 * tests the arrangement that actually shipped rather than a tidied version of it.
 */
class ReleaseAssetsTest {

    private val v140 = listOf(
        "ai-plugin-2.0.0.jar",
        "bing-services-1.0.0.jar",
        "csv-services-1.0.0.jar",
        "deepl-services-1.1.0.jar",
        "google-services-1.0.0.jar",
        "libretranslate-services-1.0.0.jar",
        "mozhi-services-1.0.0.jar",
        "mymemory-services-1.0.0.jar",
        "QTranslate-1.4.0-windows-x64.zip",
        "QTranslate-1.4.0.zip",
        "QTranslate-App-1.4.0.jar",
        "release-metadata.json",
        "reverso-services-1.0.0.jar",
        "SHA256SUMS.txt",
        "SIZE_REPORT.md",
        "wikimedia-reference-1.0.0.jar",
        "yandex-web-services-1.0.0.jar",
    )

    private val v130 = listOf(
        "ai-plugin-2.0.0.jar",
        "bing-services-1.0.0.jar",
        "deepl-services-1.1.0.jar",
        "google-services-1.0.0.jar",
        "libretranslate-services-1.0.0.jar",
        "mozhi-services-1.0.0.jar",
        "mymemory-services-1.0.0.jar",
        "QTranslate-1.3.0-windows-x64.zip",
        "QTranslate-1.3.0.zip",
        "QTranslate-App-1.3.0.jar",
        "release-metadata.json",
        "reverso-services-1.0.0.jar",
        "SHA256SUMS.txt",
        "SIZE_REPORT.md",
        "wikimedia-reference-1.0.0.jar",
        "yandex-web-services-1.0.0.jar",
    )

    @Test
    fun `Windows is offered the packaged build`() {
        assertEquals("QTranslate-1.4.0-windows-x64.zip", ReleaseAssets.selectDownload(v140, onWindows = true))
    }

    @Test
    fun `everywhere else is offered the portable archive`() {
        assertEquals("QTranslate-1.4.0.zip", ReleaseAssets.selectDownload(v140, onWindows = false))
    }

    @Test
    fun `the release that shipped the bug selects correctly too`() {
        assertEquals("QTranslate-1.3.0-windows-x64.zip", ReleaseAssets.selectDownload(v130, onWindows = true))
        assertEquals("QTranslate-1.3.0.zip", ReleaseAssets.selectDownload(v130, onWindows = false))
    }

    @Test
    fun `no plugin jar is ever chosen`() {
        // The whole point. Stated as a property over both real releases and both platforms rather
        // than as one assertion about the alphabetically first name, so a future plugin sorting
        // ahead of ai-plugin cannot quietly reintroduce this.
        listOf(v130, v140).forEach { assets ->
            listOf(true, false).forEach { onWindows ->
                val chosen = ReleaseAssets.selectDownload(assets, onWindows)
                assertTrue(
                    chosen != null && chosen.startsWith("QTranslate", ignoreCase = true),
                    "Chose '$chosen', which is not the application"
                )
            }
        }
    }

    @Test
    fun `the portable archive is not mistaken for the Windows package`() {
        // Both begin QTranslate- and end .zip. Only the absence of a further hyphenated part
        // separates them, which is the fiddliest part of the matching.
        val portableOnly = listOf("ai-plugin-2.0.0.jar", "QTranslate-1.4.0.zip")
        assertEquals("QTranslate-1.4.0.zip", ReleaseAssets.selectDownload(portableOnly, onWindows = true))

        val windowsOnly = listOf("ai-plugin-2.0.0.jar", "QTranslate-1.4.0-windows-x64.zip")
        assertNull(
            ReleaseAssets.selectDownload(windowsOnly, onWindows = false),
            "A non-Windows user must not be handed the Windows package with its bundled runtime"
        )
    }

    @Test
    fun `the app-only jar is the last resort, not the first choice`() {
        val everything = listOf("QTranslate-App-1.4.0.jar", "QTranslate-1.4.0.zip")
        assertEquals("QTranslate-1.4.0.zip", ReleaseAssets.selectDownload(everything, onWindows = false))

        val jarOnly = listOf("ai-plugin-2.0.0.jar", "QTranslate-App-1.4.0.jar")
        assertEquals("QTranslate-App-1.4.0.jar", ReleaseAssets.selectDownload(jarOnly, onWindows = false))
    }

    @Test
    fun `an unrecognisable release yields nothing rather than a guess`() {
        // So the caller can fall back to the release page. Returning "something" here is exactly
        // how a plugin ended up being served as an update.
        val noAppAssets = listOf("ai-plugin-2.0.0.jar", "SHA256SUMS.txt", "release-metadata.json")
        assertNull(ReleaseAssets.selectDownload(noAppAssets, onWindows = true))
        assertNull(ReleaseAssets.selectDownload(noAppAssets, onWindows = false))
        assertNull(ReleaseAssets.selectDownload(emptyList(), onWindows = true))
    }

    @Test
    fun `the checksum and metadata files are never offered`() {
        val decoys = listOf("SHA256SUMS.txt", "SIZE_REPORT.md", "release-metadata.json")
        assertNull(ReleaseAssets.selectDownload(decoys, onWindows = true))
    }
}
