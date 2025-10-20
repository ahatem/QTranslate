package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.dictionary.Definition
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.DictionaryResponse
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.ahatem.qtranslate.plugins.google.common.TranslateResponse
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding

class GoogleDictionaryService(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig
) : Dictionary {

    override val id: String = "google-dictionary"
    override val name: String = "Google Dictionary"
    override val version: String = "1.0.0"

    private val parser = createJsonParser<TranslateResponse>(pluginContext)

    companion object {
        private const val TRANSLATE_PRIMARY = "https://translate.googleapis.com/translate_a/single"
        private val DICTIONARY_FEATURES = listOf("bd", "ex", "md")
    }

    override suspend fun getSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> {
        return languageMapper.getSupportedLanguages()
    }

    override suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError> =
        coroutineBinding {
            val langTag = languageMapper.toProviderCode(request.language)

            val responseString = httpClient.get(
                url = TRANSLATE_PRIMARY,
                headers = apiConfig.createHeaders(),
                queryParams = mapOf(
                    "client" to "gtx",
                    "dj" to 1,
                    "dt" to DICTIONARY_FEATURES,
                    "sl" to langTag,
                    "tl" to langTag,
                    "q" to request.word
                )
            ).bind()

            val parsed = parser.parse(responseString).bind()

            val primaryEntries = parsed.dictionary?.map { dict ->
                val definitions = mutableListOf<Definition>()

                dict.entries.forEach { term ->
                    definitions += Definition(
                        text = term.word,
                        example = term.reverseTranslations.firstOrNull()
                    )
                }

                parsed.definitions
                    ?.filter { it.pos.equals(dict.pos, ignoreCase = true) }
                    ?.flatMap { it.entries }
                    ?.forEach { def ->
                        definitions += Definition(
                            text = def.gloss,
                            example = def.example
                        )
                    }

                val synonyms = dict.terms
                    .filter { it != dict.baseForm }
                    .distinct()
                    .take(10)

                DictionaryEntry(
                    word = dict.baseForm ?: request.word,
                    partOfSpeech = dict.pos,
                    definitions = definitions.distinctBy { it.text }.take(10),
                    synonyms = synonyms,
                    phonetic = null
                )
            } ?: emptyList()

            val fallbackEntries = if (primaryEntries.isEmpty() && parsed.definitions != null) {
                parsed.definitions.map { def ->
                    val definitions = def.entries.map { entry ->
                        Definition(
                            text = entry.gloss,
                            example = entry.example
                                ?: parsed.examples?.example
                                    ?.firstOrNull { it.definitionId == entry.definitionId }
                                    ?.text
                        )
                    }

                    DictionaryEntry(
                        word = def.baseForm ?: request.word,
                        partOfSpeech = def.pos,
                        definitions = definitions,
                        synonyms = emptyList(),
                        phonetic = null
                    )
                }
            } else emptyList()

            val result = DictionaryResponse(
                entries = primaryEntries.ifEmpty { fallbackEntries }
            )

            result
        }
}