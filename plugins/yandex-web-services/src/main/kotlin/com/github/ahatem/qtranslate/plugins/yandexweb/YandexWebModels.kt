package com.github.ahatem.qtranslate.plugins.yandexweb

import kotlinx.serialization.Serializable

@Serializable
internal data class YandexWebResponse(
    val text: String = "",
    val from: String = "",
    val to: String = ""
)
