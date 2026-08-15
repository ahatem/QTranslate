package com.github.ahatem.qtranslate.plugins.reverso

import kotlinx.serialization.Serializable

@Serializable
internal data class ReversoTextRequest(
    val source: String,
    val direction: String,
    val deviceId: String = "0",
    val uiLang: String = "en",
    val origin: String = "chromeextension",
    val accessToken: String = "",
    val appId: String = "0"
)

@Serializable
internal data class ReversoWordRequest(
    val source: String,
    val word: String,
    val wordPos: String = "0",
    val direction: String,
    val pageUrl: String = "0",
    val pageTitle: String = "0",
    val reversoPage: String = "null",
    val deviceId: String = "0",
    val uiLang: String = "en",
    val origin: String = "chromeextension",
    val accessToken: String = "",
    val appId: String = "0"
)

@Serializable
internal data class ReversoTextResponse(
    val error: Boolean = false,
    val success: Boolean = false,
    val message: String = "",
    val translation: String = "",
    val directionFrom: String? = null,
    val directionTo: String? = null
)

@Serializable
internal data class ReversoWordResponse(
    val error: Boolean = false,
    val success: Boolean = false,
    val message: String = "",
    val sources: List<ReversoSource> = emptyList()
)

@Serializable
internal data class ReversoSource(
    val source: String = "",
    val displaySource: String = "",
    val translations: List<ReversoWordTranslation> = emptyList()
)

@Serializable
internal data class ReversoWordTranslation(
    val translation: String = "",
    val contexts: List<ReversoContext> = emptyList(),
    val pos: String = "",
    val isRude: Boolean = false,
    val isSlang: Boolean = false
)

@Serializable
internal data class ReversoContext(
    val source: String = "",
    val target: String = "",
    val isGood: Boolean = true
)
