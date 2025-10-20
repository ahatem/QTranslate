package com.github.ahatem.qtranslate.core.updater

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.updater.data.GitHubReleaseResponse
import com.github.ahatem.qtranslate.core.updater.data.VersionInfo
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.runCatching
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class Updater(
    private val repoOwner: String,
    private val repoName: String,
    private val logger: Logger
) {

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    suspend fun getLatestVersionInfo(): Result<VersionInfo, Throwable> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
                val response: GitHubReleaseResponse = httpClient.get(url).body()

                VersionInfo(
                    versionTag = response.tagName,
                    releaseName = response.name,
                    releaseNotes = response.body,
                    downloadUrl = response.assets.firstOrNull()?.downloadUrl
                )
            }.onFailure {
                logger.error("Failed to fetch latest release info from $repoOwner/$repoName")
            }
        }

    fun close() {
        httpClient.close()
    }
}