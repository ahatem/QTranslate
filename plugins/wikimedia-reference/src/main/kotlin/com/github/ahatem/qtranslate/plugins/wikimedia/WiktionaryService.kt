package com.github.ahatem.qtranslate.plugins.wikimedia


import com.github.ahatem.qtranslate.api.dictionary.Definition
import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.DictionaryResponse
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding

internal class WiktionaryService(private val client: WikimediaClient) : Dictionary {
    override val key = "wikimedia-wiktionary"
    override val name = "Wiktionary"
    override val iconPath = "assets/wiktionary.svg"
    override val version = "1.0.0"
    override val supportedLanguages = SupportedLanguages.Specific(WikimediaLanguages.supported)

    override suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError> =
        coroutineBinding {
            validateLanguage(request.language)?.let { return@coroutineBinding Err(it).bind() }
            val language = WikimediaLanguages.editionCode(request.language)
            val page = client.search("wiktionary", language, request.word).bind()
                ?: return@coroutineBinding DictionaryResponse(emptyList())
            val response = client.pageWithHtml("wiktionary", language, page.key).bind()

            DictionaryResponse(
                WiktionaryParser.parse(response.html).map { group ->
                    DictionaryEntry(
                        word = response.title,
                        partOfSpeech = group.heading,
                        definitions = group.definitions.map(::Definition)
                    )
                }
            )
        }

    private fun validateLanguage(language: LanguageCode): ServiceError.UnsupportedLanguageError? =
        language.takeUnless { it in WikimediaLanguages.supported }?.let {
            ServiceError.UnsupportedLanguageError(it, "Wiktionary does not support language '${it.tag}'.")
        }
}
