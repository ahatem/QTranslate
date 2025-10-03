package com.github.ahatem.qtranslate.models

data class TranslationResult(
    val service: String,
    val text: String,
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val translatedText: String,
    val additionalInfo: String
)

data class Translation(
    val detectedLanguage: String,
    val translatedText: String,
    val additionalInfo: String = ""
)

data class SpellCheckResult(val correctedText: String, val corrections: List<SpellCheckCorrection>)
data class SpellCheck(val correctedText: String, val corrections: List<SpellCheckCorrection> = emptyList())
data class SpellCheckCorrection(
    val originalWord: String,
    val suggestions: List<String>,
    val startIndex: Int,
    val endIndex: Int
)

data class TextToSpeechResult(
    val content: ByteArray
)