package com.github.ahatem.qtranslate.plugins.libretranslate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SingleTranslateBody(
    val q: String,
    val source: String,
    val target: String,
    val format: String = "text",
    @SerialName("api_key") val apiKey: String? = null
)

@Serializable
internal data class BatchTranslateBody(
    val q: List<String>,
    val source: String,
    val target: String,
    val format: String = "text",
    @SerialName("api_key") val apiKey: String? = null
)

@Serializable
internal data class SingleTranslateResponse(
    val translatedText: String,
    val detectedLanguage: DetectedLanguage? = null
)

@Serializable
internal data class BatchTranslateResponse(
    val translatedText: List<String>,
    val detectedLanguage: List<DetectedLanguage>? = null
)

@Serializable
internal data class DetectedLanguage(
    val language: String,
    val confidence: Double? = null
)

@Serializable
internal data class LibreTranslateLanguage(
    val code: String,
    val name: String? = null,
    val targets: List<String> = emptyList()
)
