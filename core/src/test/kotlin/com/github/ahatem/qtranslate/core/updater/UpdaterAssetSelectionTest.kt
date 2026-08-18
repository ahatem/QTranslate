package com.github.ahatem.qtranslate.core.updater

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.updater.data.UpdateCheckResult
import com.github.michaelbull.result.get
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The same defect end to end, through the real JSON the GitHub API returns.
 *
 * [ReleaseAssetsTest] covers the choosing. This covers the wiring around it, and one thing that
 * test cannot see: the asset name has to be parsed off the wire at all. `GitHubAsset` only ever
 * read `browser_download_url`, so before this fix there was no name to match on, and adding the
 * field is as load-bearing as the matching itself.
 */
class UpdaterAssetSelectionTest {

    private val silentLogger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    /** Shaped like the real response, plugin JARs first, as GitHub orders them. */
    private fun releaseJson(tag: String = "v9.9.9") = """
        {
          "tag_name": "$tag",
          "name": "QTranslate 9.9.9",
          "body": "notes",
          "html_url": "https://github.com/ahatem/QTranslate/releases/tag/$tag",
          "assets": [
            {"name": "ai-plugin-2.0.0.jar", "browser_download_url": "https://example.invalid/ai-plugin-2.0.0.jar"},
            {"name": "bing-services-1.0.0.jar", "browser_download_url": "https://example.invalid/bing-services-1.0.0.jar"},
            {"name": "QTranslate-9.9.9-windows-x64.zip", "browser_download_url": "https://example.invalid/QTranslate-9.9.9-windows-x64.zip"},
            {"name": "QTranslate-9.9.9.zip", "browser_download_url": "https://example.invalid/QTranslate-9.9.9.zip"},
            {"name": "QTranslate-App-9.9.9.jar", "browser_download_url": "https://example.invalid/QTranslate-App-9.9.9.jar"},
            {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.invalid/SHA256SUMS.txt"}
          ]
        }
    """.trimIndent()

    private fun updaterReturning(body: String): Updater {
        val engine = MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        return Updater("ahatem", "qtranslate", client, silentLogger)
    }

    @Test
    fun `the offered download is the application, never the first asset`() = runTest {
        val result = updaterReturning(releaseJson()).checkForUpdate(currentVersion = "1.0.0")

        val available = result.get() as UpdateCheckResult.UpdateAvailable
        val url = available.info.downloadUrl
        assertNotNull(url, "An update with application assets must offer a download")

        // Windows or not, it must be one of the application archives. Asserted as a property
        // because the platform decides which, and CI is not Windows.
        assertTrue(
            url.endsWith("QTranslate-9.9.9-windows-x64.zip") || url.endsWith("QTranslate-9.9.9.zip"),
            "Offered '$url', which is not the application"
        )
        assertTrue("plugin" !in url && "services" !in url, "Offered a plugin: $url")
    }

    @Test
    fun `a release with no application assets falls back to the release page`() = runTest {
        val pluginsOnly = """
            {
              "tag_name": "v9.9.9",
              "name": "QTranslate 9.9.9",
              "body": "notes",
              "html_url": "https://github.com/ahatem/QTranslate/releases/tag/v9.9.9",
              "assets": [
                {"name": "ai-plugin-2.0.0.jar", "browser_download_url": "https://example.invalid/ai-plugin-2.0.0.jar"}
              ]
            }
        """.trimIndent()

        val result = updaterReturning(pluginsOnly).checkForUpdate(currentVersion = "1.0.0")

        val available = result.get() as UpdateCheckResult.UpdateAvailable
        // The page, so a person can choose, rather than the one plugin that happens to be there.
        assertTrue(
            available.info.downloadUrl == "https://github.com/ahatem/QTranslate/releases/tag/v9.9.9",
            "Expected the release page, got ${available.info.downloadUrl}"
        )
    }
}
