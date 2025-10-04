package com.github.ahatem.qtranslate.api


import com.github.michaelbull.result.Result

/**
 * A service that provides word definitions.
 */
interface Dictionary : Service, LanguageSupport {
    suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError>
}

data class DictionaryRequest(
    val word: String,
    val language: LanguageCode
)

data class DictionaryResponse(
    val entries: List<DictionaryEntry>
)

data class DictionaryEntry(
    val word: String,
    val partOfSpeech: String,
    val definitions: List<Definition>,
    val synonyms: List<String> = emptyList(),
    val phonetic: String? = null
)

data class Definition(
    val text: String,
    val example: String? = null
)