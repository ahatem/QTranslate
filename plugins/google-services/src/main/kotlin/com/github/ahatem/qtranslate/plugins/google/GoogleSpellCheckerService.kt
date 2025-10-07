package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.*
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.api.spellchecker.CorrectionType
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckResponse
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.ahatem.qtranslate.plugins.google.common.TranslateResponse
import com.github.michaelbull.result.*
import com.github.michaelbull.result.coroutines.coroutineBinding
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.*

class GoogleSpellCheckerService(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig
) : SpellChecker {

    override val id: String = "google-services-spell-checker"
    override val name: String = "Google Spell Checker"
    override val version: String = "1.0.0"

    private val parser = createJsonParser<TranslateResponse>(pluginContext)

    private val cache = Collections.synchronizedMap(object :
        LinkedHashMap<String, Result<SpellCheckResponse, ServiceError>>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result<SpellCheckResponse, ServiceError>>?): Boolean {
            return size > 200
        }
    })

    companion object {
        private const val TRANSLATE_PRIMARY = "https://translate.googleapis.com/translate_a/single"
    }

    override suspend fun getSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> {
        return languageMapper.getSupportedLanguages()
    }

    override suspend fun check(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError> = coroutineScope {
        if (request.text.isBlank()) {
            return@coroutineScope Ok(SpellCheckResponse(request.text, emptyList()))
        }

        val sentences = request.text.split(Regex("(?<=[.?!])\\s*")).filter { it.isNotBlank() }
        var currentOffset = 0

        val deferredResults = sentences.map { sentence ->
            val sentenceOffset = request.text.indexOf(sentence, currentOffset)
            currentOffset = sentenceOffset + sentence.length
            async {
                val cachedResult = cache[sentence]
                if (cachedResult != null) {
                    return@async cachedResult to sentenceOffset
                }

                val result = checkSentence(sentence, request.language)
                result.onSuccess {
                    cache[sentence] = Ok(it)
                }
                result to sentenceOffset
            }
        }

        val resultsWithOffsets = deferredResults.awaitAll()

        val allCorrections = mutableListOf<Correction>()
        val correctedSentences = mutableListOf<String>()

        for ((result, offset) in resultsWithOffsets) {
            result.fold(
                success = { value ->
                    correctedSentences.add(value.correctedText)
                    value.corrections.forEach { correction ->
                        allCorrections.add(
                            correction.copy(
                                startIndex = offset + correction.startIndex,
                                endIndex = offset + correction.endIndex
                            )
                        )
                    }
                },
                failure = { return@coroutineScope result }
            )

        }

        Ok(
            SpellCheckResponse(
                correctedText = correctedSentences.joinToString(" "),
                corrections = allCorrections
            )
        )
    }

    private suspend fun checkSentence(
        sentence: String,
        language: LanguageCode
    ): Result<SpellCheckResponse, ServiceError> {
        return coroutineBinding {
            val langTag = languageMapper.toProviderCode(language)
            val responseString = httpClient.get(
                url = TRANSLATE_PRIMARY,
                headers = apiConfig.createHeaders(),
                queryParams = mapOf(
                    "client" to "gtx",
                    "dj" to 1,
                    "sl" to langTag,
                    "tl" to "zu",
                    "q" to sentence,
                    "dt" to "qc"
                )
            ).bind()
            parseSpellCheck(responseString, sentence).bind()
        }
    }

    private suspend fun parseSpellCheck(
        responseString: String,
        requestText: String
    ): Result<SpellCheckResponse, ServiceError> {
        return parser.parse(responseString).andThen { translateResponse ->
            val spell = translateResponse.spell
            val correctedText = spell?.correctedText
            val html = spell?.spellHtmlRes

            if (spell == null || correctedText == null || html == null) {
                return@andThen Ok(SpellCheckResponse(correctedText = requestText, corrections = emptyList()))
            }

            val pattern = Regex("<b><i>(.*?)</i></b>")
            val matches = pattern.findAll(html).map { it.groupValues[1] }.toList()

            val corrections = mutableListOf<Correction>()

            if (matches.isNotEmpty()) {
                val correctedWords = correctedText.split(" ")
                val originalWords = requestText.split(" ")

                for (i in correctedWords.indices) {
                    val corrected = correctedWords[i]
                    val original = originalWords.getOrNull(i)
                    if (original != null && original != corrected) {
                        val start = requestText.indexOf(original)
                        if (start > -1) {
                            val end = start + original.length
                            corrections.add(
                                Correction(
                                    original = original,
                                    startIndex = start,
                                    endIndex = end,
                                    suggestions = listOf(corrected),
                                    type = CorrectionType.SPELLING,
                                    message = "Google spell suggestion"
                                )
                            )
                        }
                    }
                }
            }

            Ok(SpellCheckResponse(correctedText = correctedText, corrections = corrections))
        }
    }
}
