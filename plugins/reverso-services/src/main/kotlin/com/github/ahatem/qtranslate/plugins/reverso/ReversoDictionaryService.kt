package com.github.ahatem.qtranslate.plugins.reverso

import com.github.ahatem.qtranslate.api.dictionary.Definition
import com.github.ahatem.qtranslate.api.dictionary.BilingualDictionary
import com.github.ahatem.qtranslate.api.dictionary.BilingualDictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.DictionaryResponse
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import org.jsoup.Jsoup

internal class ReversoDictionaryService(
    private val client: ReversoClient
) : BilingualDictionary {
    override val id = "reverso-services-dictionary"
    override val name = "Reverso Dictionary"
    override val version = "1.0.0"
    override val iconPath = "assets/reverso.png"
    override val supportedLanguages = ReversoLanguageMapper.supportedLanguages

    override suspend fun lookup(request: DictionaryRequest): Result<DictionaryResponse, ServiceError> {
        val targetLanguage = defaultTargetLanguage(request.language)
        return lookup(request.word, request.language, targetLanguage)
    }

    override suspend fun lookupBilingual(
        request: BilingualDictionaryRequest
    ): Result<DictionaryResponse, ServiceError> = lookup(
        request.word,
        request.sourceLanguage,
        request.targetLanguage.takeUnless { it == request.sourceLanguage }
            ?: defaultTargetLanguage(request.sourceLanguage)
    )

    private suspend fun lookup(
        word: String,
        sourceLanguage: LanguageCode,
        targetLanguage: LanguageCode
    ): Result<DictionaryResponse, ServiceError> {
        validateLanguage(sourceLanguage)?.let { return Err(it) }
        validateLanguage(targetLanguage)?.let { return Err(it) }

        return coroutineBinding {
            val response = client.lookup(ReversoWordRequest(
                source = word,
                word = word,
                direction = "${ReversoLanguageMapper.code(sourceLanguage)}-${ReversoLanguageMapper.code(targetLanguage)}"
            )).bind()
            if (!response.success || response.error) Err(client.providerError(response.message)).bind<Unit>()

            DictionaryResponse(entries = response.sources.flatMap { source ->
                source.translations
                    .filter { it.translation.isNotBlank() }
                    .take(MAX_TRANSLATIONS)
                    .map { translation -> translation.toEntry(source, word) }
            })
        }
    }

    private fun ReversoWordTranslation.toEntry(
        source: ReversoSource,
        requestedWord: String
    ): DictionaryEntry {
        val translatedTerm = clean(translation)
        val examples = contexts.asSequence()
            .filter { it.isGood && it.source.isNotBlank() && it.target.isNotBlank() }
            .map { context -> Definition(
                text = clean(context.target),
                example = clean(context.source)
            ) }
            .distinctBy { it.text to it.example }
            .take(MAX_CONTEXTS_PER_TRANSLATION)
            .toList()

        return DictionaryEntry(
            word = source.displaySource.ifBlank { source.source.ifBlank { requestedWord } },
            partOfSpeech = buildList {
                add(readablePartOfSpeech(pos))
                if (isSlang) add("slang")
                if (isRude) add("informal")
                add(translatedTerm)
            }.filter(String::isNotBlank).joinToString(" - "),
            definitions = examples.ifEmpty { listOf(Definition(text = translatedTerm)) }
        )
    }

    private fun defaultTargetLanguage(sourceLanguage: LanguageCode): LanguageCode =
        if (sourceLanguage == LanguageCode.ENGLISH) LanguageCode.FRENCH else LanguageCode.ENGLISH

    private fun validateLanguage(language: LanguageCode): ServiceError? =
        if (language !in ReversoLanguageMapper.languages) {
            ServiceError.UnsupportedLanguageError(
                language,
                "Reverso Dictionary does not support ${language.tag}."
            )
        } else null

    private fun clean(html: String): String = Jsoup.parseBodyFragment(html).text().trim()

    private fun readablePartOfSpeech(value: String): String = when (value.lowercase()) {
        "n", "nm", "nf" -> "noun"
        "v", "vi", "vt", "vr" -> "verb"
        "adj" -> "adjective"
        "adv" -> "adverb"
        "prep" -> "preposition"
        "pron" -> "pronoun"
        else -> value
    }

    private companion object {
        const val MAX_TRANSLATIONS = 10
        const val MAX_CONTEXTS_PER_TRANSLATION = 3
    }
}
