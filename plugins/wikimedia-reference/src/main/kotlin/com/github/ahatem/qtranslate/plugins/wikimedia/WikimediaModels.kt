package com.github.ahatem.qtranslate.plugins.wikimedia

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class WikimediaSearchResponse(
    val pages: List<WikimediaSearchPage> = emptyList()
)

@Serializable
internal data class WikimediaSearchPage(
    val key: String,
    val title: String,
    val excerpt: String = "",
    val description: String? = null
)

@Serializable
internal data class WikimediaPageResponse(
    val key: String,
    val title: String,
    val html: String
)

internal data class WiktionarySenseGroup(
    val heading: String,
    val definitions: List<String>
)
