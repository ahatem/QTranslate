package com.pnix.qtranslate.models

data class TranslationResult(
    val service: String,
    val text: String,
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val translatedText: String,
)

/** first string for language of the translated text in second string => (detectLanguage, translatedText) */
data class Translation(
  val detectedLanguage: String,
  val translatedText: String
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