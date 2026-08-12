package com.github.ahatem.qtranslate.plugins.mozhi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class MozhiTranslationResponse(
    @SerialName("translated-text") val translatedText: String,
    val detected: String? = null,
    @SerialName("source_language") val sourceLanguage: String? = null,
    @SerialName("target_transliteration") val targetTransliteration: String? = null
)
