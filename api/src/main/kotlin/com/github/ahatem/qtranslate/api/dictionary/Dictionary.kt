package com.github.ahatem.qtranslate.api.dictionary

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Result

/**
 * A service that looks up word definitions, synonyms, and phonetics.
 *
 * Language support is declared via [Service.supportedLanguages]. Most dictionary
 * services support a fixed set of languages — use
 * [com.github.ahatem.qtranslate.api.plugin.SupportedLanguages.Specific] for these.
 */
interface Dictionary : Service {

    /**
     * Looks up the definition and related metadata for the word in [request].
     *
     * @param request The lookup parameters, including the word and its language.
     * @return `Ok` with the [DictionaryResponse] on success, or an `Err` with a [ServiceError].
     *         Returns [ServiceError.InvalidInputError] if the word is blank.
     *         Returns [ServiceError.UnsupportedLanguageError] if the language is not supported.
     */
    suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError>
}

/**
 * Parameters for a dictionary lookup.
 *
 * @param word     The word to look up. Must not be blank.
 * @param language The language of [word].
 */
data class DictionaryRequest(
    val word: String,
    val language: LanguageCode
) {
    init {
        require(word.isNotBlank()) { "Dictionary lookup word must not be blank." }
    }
}

/**
 * The result of a successful dictionary lookup.
 *
 * A single word may have multiple entries (e.g. "bass" as a fish vs. "bass" as a
 * musical term), each with its own part of speech and definitions.
 *
 * @param entries The list of dictionary entries found for the word. May be empty
 *                if the word exists but has no entries in this service's database.
 */
data class DictionaryResponse(
    val entries: List<DictionaryEntry>
)

/**
 * A single dictionary entry, representing one sense or part-of-speech grouping of a word.
 *
 * @param word        The canonical form of the word as the service recognises it.
 * @param partOfSpeech The grammatical category (e.g. `"noun"`, `"verb"`, `"adjective"`).
 * @param definitions  The list of definitions for this entry.
 * @param synonyms     Optional list of synonyms.
 * @param phonetic     Optional phonetic representation (e.g. IPA notation: `"/bæs/"`).
 */
data class DictionaryEntry(
    val word: String,
    val partOfSpeech: String,
    val definitions: List<Definition>,
    val synonyms: List<String> = emptyList(),
    val phonetic: String? = null
)

/**
 * A single definition within a [DictionaryEntry].
 *
 * @param text    The definition text.
 * @param example An optional example sentence illustrating the word's usage.
 */
data class Definition(
    val text: String,
    val example: String? = null
)