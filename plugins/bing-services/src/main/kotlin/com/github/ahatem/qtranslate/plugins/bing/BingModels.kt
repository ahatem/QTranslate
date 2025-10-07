package com.github.ahatem.qtranslate.plugins.bing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BingAuth(
    val ig: String,
    val iid: String,
    val key: String,
    val token: String,
    val muid: String,
    val sid: String,
    val tid: String
)

@Serializable
data class BingTranslateResponse(
    @SerialName("translations") val translations: List<BingTranslation>? = null,
    @SerialName("detectedLanguage") val detectedLanguage: BingDetectedLanguage? = null
)

@Serializable
data class BingDetectedLanguage(
    @SerialName("language") val language: String,
    @SerialName("score") val score: Double? = null
)

@Serializable
data class BingTranslation(
    @SerialName("text") val text: String,
    @SerialName("to") val to: String
)

@Serializable
data class BingSpellCheckResponse(
    @SerialName("correctedText") val correctedText: String = ""
)