package com.github.ahatem.qtranslate.core.settings.data

import kotlinx.serialization.Serializable

@Serializable
data class TranslationRule(
    val sourceLanguage: String,
    val targetLanguage: String
)
