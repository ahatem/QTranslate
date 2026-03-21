package com.github.ahatem.qtranslate.core.updater.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for the GitHub Releases API response.
 * `GET https://api.github.com/repos/{owner}/{repo}/releases/latest`
 *
 * This is an internal parsing type — it is immediately mapped to [VersionInfo]
 * and never exposed outside the `updater` package.
 */
@Serializable
internal data class GitHubReleaseResponse(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String,
    @SerialName("body") val releaseNotes: String,
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList()
)

@Serializable
internal data class GitHubAsset(
    @SerialName("browser_download_url") val downloadUrl: String
)