package com.github.ahatem.qtranslate.plugins.google


import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.api.spellchecker.CorrectionType
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckResponse
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.ahatem.qtranslate.plugins.common.getOnce
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.ahatem.qtranslate.plugins.google.common.TranslateResponse
import com.github.michaelbull.result.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.*

class GoogleSpellCheckerService(
    private val pluginContext: PluginContext,
    private val httpClient: HttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig,
    private val endpointHealth: GoogleEndpointHealth
) : SpellChecker {


    override val key: String = "google-spell-checker"
    override val name: String = "Google Spell Checker"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/google-translate-icon.svg"

    private val parser = createJsonParser<TranslateResponse>(pluginContext)

    private val requestSemaphore = Semaphore(SPELL_CHECK_MAX_CONCURRENCY)

    private val cache = Collections.synchronizedMap(object :
        LinkedHashMap<String, Result<SpellCheckResponse, ServiceError>>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result<SpellCheckResponse, ServiceError>>?): Boolean {
            return size > 200
        }
    })

    companion object {
        private const val TRANSLATE_PRIMARY = "https://translate.googleapis.com/translate_a/single"
        private const val SPELL_CHECK_MAX_CONCURRENCY = 4
    }

    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        languageMapper.getSupportedLanguages()

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
                result.onOk {
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
        val langTag = languageMapper.toProviderCode(language)

        return requestSemaphore.withPermit {
            // The circuit permit is taken only once a request slot is free, so a sentence queued
            // while the endpoint was healthy cannot slip past a circuit that opened in the meantime.
            val permit = endpointHealth.tryAcquirePrimary()
                ?: return@withPermit Err(
                    ServiceError.ServiceUnavailableError(
                        "Google spell check endpoint is temporarily unavailable",
                        null
                    )
                )

            // Released on every path exactly once, including cancellation, so an abandoned probe
            // never strands the circuit.
            try {
                val response = httpClient.getOnce(
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
                )

                response.fold(
                    success = { body ->
                        currentCoroutineContext().ensureActive()
                        val parsed = parseSpellCheck(body, sentence)
                        // Only a recognised payload heals the circuit. An unrecognised one means the
                        // endpoint answered unusably, which is neutral: the finally releases the
                        // permit without healing or counting a failure.
                        if (parsed.isOk) endpointHealth.recordSuccess(permit)
                        parsed
                    },
                    failure = { error ->
                        currentCoroutineContext().ensureActive()
                        // A non-retryable failure leaves the permit to the finally, which records
                        // nothing on the circuit.
                        if (error.isRetryable) endpointHealth.recordTransientFailure(permit, error)
                        Err(error)
                    }
                )
            } finally {
                endpointHealth.releasePrimary(permit)
            }
        }
    }

    private suspend fun parseSpellCheck(
        responseString: String,
        requestText: String
    ): Result<SpellCheckResponse, ServiceError> {
        return parser.parse(responseString).andThen { translateResponse ->
            // A throttled or errored body decodes into an all-defaults payload under the lenient
            // parser; treat anything without a translate or spell section as unrecognised so it is
            // never mistaken for "no corrections" and cached.
            if (translateResponse.sentences.isEmpty() &&
                translateResponse.spell == null &&
                translateResponse.sourceLanguage.isBlank()
            ) {
                return@andThen Err(ServiceError.InvalidResponseError("Unrecognised spell check response", null))
            }

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
