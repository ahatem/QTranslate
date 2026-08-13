package com.github.ahatem.qtranslate.plugins.yandexweb

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
internal data class YandexWebResponse(
    val text: String = "",
    val from: String = "",
    val to: String = ""
)

@Serializable
internal data class MozhiYandexResponse(
    @SerialName("translated-text") val translatedText: String = "",
    @SerialName("source_language") val sourceLanguage: String = ""
)
