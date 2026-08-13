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
    @SerialName("detected_source_language") val detectedSourceLanguage: String? = null
)

@Serializable
internal data class DeepLLanguage(
    val language: String
)

@Serializable
internal data class DeepLWebRequest(
    val jsonrpc: String = "2.0",
    val method: String = "LMT_handle_texts",
    val id: Long,
    val params: DeepLWebParams
)

@Serializable
internal data class DeepLWebParams(
    val splitting: String = "newlines",
    val lang: DeepLWebLanguage,
    val texts: List<DeepLWebText>,
    val timestamp: Long
)

@Serializable
internal data class DeepLWebLanguage(
    @SerialName("source_lang_user_selected") val sourceLanguage: String,
    @SerialName("target_lang") val targetLanguage: String
)

@Serializable
internal data class DeepLWebText(
    val text: String,
    val requestAlternatives: Int = 3
)

@Serializable
internal data class DeepLWebResponse(
    val result: DeepLWebResult? = null,
    val error: DeepLWebError? = null
)

@Serializable
internal data class DeepLWebResult(
    val texts: List<DeepLWebTranslation> = emptyList(),
    val lang: String? = null
)

@Serializable
internal data class DeepLWebTranslation(
    val text: String = "",
    val alternatives: List<DeepLWebAlternative> = emptyList()
)

@Serializable
internal data class DeepLWebAlternative(val text: String = "")

@Serializable
internal data class DeepLWebError(
    val code: Int = 0,
    val message: String = "Unknown error"
)
