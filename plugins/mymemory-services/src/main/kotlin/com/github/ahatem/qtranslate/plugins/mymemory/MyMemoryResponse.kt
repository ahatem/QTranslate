package com.github.ahatem.qtranslate.plugins.mymemory

import kotlinx.serialization.Serializable

@Serializable
internal data class MyMemoryResponse(
    val responseData: MyMemoryResponseData? = null,
    val responseStatus: Int = 0,
    val responseDetails: String = "",
    val quotaFinished: Boolean = false
)

@Serializable
internal data class MyMemoryResponseData(
    val translatedText: String = ""
)
