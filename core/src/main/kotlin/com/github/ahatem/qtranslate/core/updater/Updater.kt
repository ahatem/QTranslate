package com.github.ahatem.qtranslate.core.updater

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.updater.data.GitHubReleaseResponse
import com.github.ahatem.qtranslate.core.updater.data.UpdateCheckResult
import com.github.ahatem.qtranslate.core.updater.data.VersionInfo
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * Checks for application updates against the GitHub Releases API.
 *
 * ### Lifecycle
 * `Updater` does not own or close the [httpClient] — the client's lifecycle
 * is managed by the application root and shared with other consumers. Do not
 * call `httpClient.close()` from here.
 *
 * ### Usage
 * ```kotlin
 * val result = updater.checkForUpdate(currentVersion = BuildInfo.VERSION)
 * when (result) {
 *     is Ok -> when (val check = result.value) {
 *         is UpdateCheckResult.UpdateAvailable  -> showUpdateDialog(check.info)
 *         is UpdateCheckResult.AlreadyUpToDate  -> logger.info("Already up to date")
 *     }
 *     is Err -> logger.warn("Update check failed: ${result.error.message}")
 * }
 * ```
 *
 * @property repoOwner  GitHub repository owner (e.g. `"ahatem"`).
 * @property repoName   GitHub repository name (e.g. `"qtranslate"`).
 * @property httpClient Shared Ktor [HttpClient] with JSON content negotiation configured.
 *   Must have [kotlinx.serialization] support installed.
 * @property logger     Logger scoped to this component.
 */
class Updater(
    private val repoOwner: String,
    private val repoName: String,
    private val httpClient: HttpClient,
    private val logger: Logger
) {
    private val releasesUrl =
        "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"

    /**
     * Fetches the latest release from GitHub and returns the full [VersionInfo].
     *
     * Use this when you need the raw release data regardless of whether it is
     * newer than the running version (e.g. for displaying the changelog).
     *
     * @return [Ok] with [VersionInfo] on success, or [Err] with an [UpdaterError].
     */
    suspend fun getLatestVersionInfo(): Result<VersionInfo, UpdaterError> =
        withContext(Dispatchers.IO) {
            fetchRelease()
        }

    /**
     * Checks whether a newer version is available and returns a [UpdateCheckResult].
     *
     * Compares the latest GitHub release tag against [currentVersion] using
     * semantic version ordering. See [VersionInfo.isNewerThan] for comparison rules.
     *
     * @param currentVersion The version string of the running application
     *   (e.g. `"1.2.0"` or `"v1.2.0"`).
     * @return [Ok] with [UpdateCheckResult.UpdateAvailable] if a newer version exists,
     *   [Ok] with [UpdateCheckResult.AlreadyUpToDate] if already current, or
     *   [Err] with an [UpdaterError] if the check could not be completed.
     */
    suspend fun checkForUpdate(currentVersion: String): Result<UpdateCheckResult, UpdaterError> =
        withContext(Dispatchers.IO) {
            fetchRelease().map { info ->
                if (info.isNewerThan(currentVersion)) {
                    logger.info("Update available: ${info.versionTag} (current: $currentVersion)")
                    UpdateCheckResult.UpdateAvailable(info)
                } else {
                    logger.info("Already up to date (version: $currentVersion)")
                    UpdateCheckResult.AlreadyUpToDate(currentVersion)
                }
            }
        }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private suspend fun fetchRelease(): Result<VersionInfo, UpdaterError> =
        try {
            val response: GitHubReleaseResponse = httpClient.get(releasesUrl) {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            }.body()

            Ok(
                VersionInfo(
                    versionTag   = response.tagName,
                    releaseName  = response.name,
                    releaseNotes = response.releaseNotes,
                    downloadUrl  = response.assets.firstOrNull()?.downloadUrl
                )
            )
        } catch (e: io.ktor.serialization.JsonConvertException) {
            logger.error("Failed to parse GitHub release response", e)
            Err(UpdaterError.ParseError("Could not parse release data: ${e.message}", e))
        } catch (e: SerializationException) {
            logger.error("Failed to parse GitHub release response", e)
            Err(UpdaterError.ParseError("Could not parse release data: ${e.message}", e))
        } catch (e: Exception) {
            logger.error("Failed to fetch latest release from $repoOwner/$repoName", e)
            Err(UpdaterError.NetworkError("Network error during update check: ${e.message}", e))
        }
}