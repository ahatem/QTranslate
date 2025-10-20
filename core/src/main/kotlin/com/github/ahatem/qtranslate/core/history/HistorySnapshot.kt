package com.github.ahatem.qtranslate.core.history

import kotlinx.serialization.Serializable

@Serializable
data class HistorySnapshot(
    val inputText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translatorId: String,
    val timestamp: Long = System.currentTimeMillis()
)
