package com.github.ahatem.qtranslate.services.translators.abstraction

import com.github.ahatem.qtranslate.models.*

abstract class TranslatorService {
    abstract val serviceName: String
    abstract val languageMapper: LanguageMapper

    val supportedLanguages get() = languageMapper.supportedLanguages.map { languageMapper.denormalize(it) }

    suspend fun translate(text: String, targetLanguage: String, sourceLanguage: String = "auto"): TranslationResult {
        if (text.isBlank()) throw IllegalArgumentException("Parameter 'text' must not be empty")

        val normalizedTargetLanguage = languageMapper.normalize(targetLanguage)
        val normalizedSourceLanguage = languageMapper.normalize(sourceLanguage)
        if (normalizedTargetLanguage == normalizedSourceLanguage) throw IllegalArgumentException("targetLanguage and sourceLanguage can not be equal")

        val (detectLanguage, translatedText, additionalInfo) = doTranslate(
            text,
            normalizedTargetLanguage,
            normalizedSourceLanguage
        )

        return TranslationResult(
            serviceName,
            text,
            languageMapper.denormalize(detectLanguage),
            languageMapper.denormalize(targetLanguage),
            translatedText,
            additionalInfo
        )
    }

    suspend fun textToSpeech(text: String, sourceLanguage: String = "auto"): TextToSpeechResult {
        if (text.isBlank()) throw IllegalArgumentException("Parameter 'text' must not be empty")
        val normalizedSourceLanguage = languageMapper.normalize(sourceLanguage)
        return doTextToSpeech(text, normalizedSourceLanguage)
    }

    suspend fun spellCheck(text: String, sourceLanguage: String = "auto"): SpellCheckResult {
        if (text.isBlank()) throw IllegalArgumentException("Parameter 'text' must not be empty")
        val normalizedSourceLanguage = languageMapper.normalize(sourceLanguage)
        var (correctedText, corrections) = doSpellCheck(text, normalizedSourceLanguage)
        if (corrections.isEmpty()) corrections = buildCorrectionsList(text, correctedText)
        return SpellCheckResult(correctedText, corrections)
    }

    protected abstract suspend fun doTranslate(
        text: String,
        targetLanguage: String,
        sourceLanguage: String
    ): Translation

    protected abstract suspend fun doTextToSpeech(text: String, sourceLanguage: String): TextToSpeechResult
    protected abstract suspend fun doSpellCheck(text: String, sourceLanguage: String): SpellCheck


    private fun buildCorrectionsList(text: String, correctedText: String): List<SpellCheckCorrection> {
        val corrections = mutableListOf<SpellCheckCorrection>()

        val words = text.split(" ")
        val correctedWords = correctedText.split(" ")

        var index = 0
        val lastIndex = words.lastIndex.coerceAtMost(correctedWords.lastIndex)

        while (index <= lastIndex) {
            if (words[index] != correctedWords[index]) {
                val startIndex = text.indexOf(words[index])
                val endIndex = startIndex + words[index].length
                corrections.add(SpellCheckCorrection(words[index], listOf(correctedWords[index]), startIndex, endIndex))
            }
            index++
        }

        return corrections
    }

}

