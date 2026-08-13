package com.github.ahatem.qtranslate.plugins.deepl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DeepLTranslateRequest(
    val text: List<String>,
    @SerialName("target_lang") val targetLanguage: String,
    @SerialName("source_lang") val sourceLanguage: String? = null
)

@Serializable
internal data class DeepLTranslateResponse(
    val translations: List<DeepLTranslation> = emptyList()
)

@Serializable
internal data class DeepLTranslation(
    val text: String,
    @SerialName("detected_source_language") val detectedSourceLanguage: String? = null,
    @SerialName("is_language_detection_confident") val isLanguageDetectionConfident: Boolean? = null
)

@Serializable
internal data class DeepLLanguage(
    val language: String
)
