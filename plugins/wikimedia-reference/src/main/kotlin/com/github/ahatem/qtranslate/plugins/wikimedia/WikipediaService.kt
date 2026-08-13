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
import com.github.michaelbull.result.map
import org.jsoup.Jsoup

internal class WikipediaService(private val client: WikimediaClient) : Dictionary {
    override val id = "wikimedia-wikipedia"
    override val name = "Wikipedia"
    override val iconPath = "assets/wikipedia.svg"
    override val version = "1.0.0"
    override val supportedLanguages = SupportedLanguages.Specific(WikimediaLanguages.supported)

    override suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError> {
        validateLanguage(request.language)?.let { return Err(it) }
        val language = WikimediaLanguages.editionCode(request.language)

        return client.search("wikipedia", language, request.word).map { page ->
            if (page == null) return@map DictionaryResponse(emptyList())
            val excerpt = Jsoup.parseBodyFragment(page.excerpt).text().trim()
            val definitions = buildList {
                page.description?.takeIf(String::isNotBlank)?.let { add(Definition(it)) }
                excerpt.takeIf(String::isNotBlank)?.let { add(Definition(it)) }
            }.distinctBy(Definition::text)

            DictionaryResponse(
                listOf(DictionaryEntry(page.title, "encyclopedia", definitions))
            )
        }
    }

    private fun validateLanguage(language: LanguageCode): ServiceError.UnsupportedLanguageError? =
        language.takeUnless { it in WikimediaLanguages.supported }?.let {
            ServiceError.UnsupportedLanguageError(it, "Wikipedia does not support language '${it.tag}'.")
        }
}
