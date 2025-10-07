package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.*
import com.github.ahatem.qtranslate.api.spellchecker.Correction
import com.github.ahatem.qtranslate.api.spellchecker.CorrectionType
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckRequest
import com.github.ahatem.qtranslate.api.spellchecker.SpellCheckResponse
import com.github.ahatem.qtranslate.api.spellchecker.SpellChecker
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.getOrElse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class BingSpellCheckerService(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val authManager: BingAuthManager,
    private val languageMapper: BingLanguageMapper,
    private val apiConfig: ApiConfig
) : SpellChecker {

    override val id: String = "bing-spell-checker"
    override val name: String = "Bing Spell Checker"
    override val version: String = "1.0.0"

    private val parser = createJsonParser<BingSpellCheckResponse>(pluginContext)

    companion object {
        private const val SPELLCHECK_URL = "https://www.bing.com/tspellcheckv3"
        private const val MAX_CHUNK_LENGTH = 1000
    }

    override suspend fun getSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        Ok(languageMapper.spellCheckLanguageCodes.toSet())

    override suspend fun check(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError> =
        coroutineScope {
            when {
                request.text.isBlank() -> Ok(SpellCheckResponse(request.text, emptyList()))
                else -> checkWithChunking(request)
            }
        }

    private suspend fun checkWithChunking(request: SpellCheckRequest): Result<SpellCheckResponse, ServiceError> =
        coroutineBinding {
            val auth = authManager.getAuth().bind()
            val language = languageMapper.toProviderCode(request.language)
            val chunks = partitionText(request.text)

            val correctedChunks = chunks
                .map { chunk -> async { checkChunk(chunk, language, auth) } }
                .awaitAll()
                .map { chunk -> chunk.getOrElse { "" } }

            val correctedText = correctedChunks.joinToString(" ").trim()
            SpellCheckResponse(
                correctedText = correctedText,
                corrections = generateCorrections(original = request.text, correctedText)
            )
        }

    private suspend fun checkChunk(
        text: String,
        language: String,
        auth: BingAuth
    ): Result<String, ServiceError> = coroutineBinding {
        val formData = mapOf(
            "text" to text,
            "fromLang" to language,
            "token" to auth.token,
            "key" to auth.key
        )

        val responseString = httpClient.postForm(
            url = SPELLCHECK_URL,
            headers = apiConfig.createHeaders(),
            formData = formData,
            queryParams = mapOf(
                "isVertical" to 1,
                "IG" to auth.ig,
                "IID" to auth.iid,
                "SFX" to 2
            ),
            cookies = mapOf("MUID" to auth.muid)
        ).bind()

        val response = parser.parse(responseString).bind()
        response.correctedText.ifEmpty { text }
    }

    private fun partitionText(text: String): List<String> =
        text.split(Regex("\\s+"))
            .fold(mutableListOf("")) { acc, word ->
                when {
                    acc.last().isEmpty() -> acc.apply { this[lastIndex] = word }
                    (acc.last() + " " + word).length > MAX_CHUNK_LENGTH -> acc.apply { add(word) }
                    else -> acc.apply { this[lastIndex] = "${this[lastIndex]} $word" }
                }
            }
            .filter { it.isNotBlank() }

    fun generateCorrections(original: String, corrected: String): List<Correction> {
        val corrections = mutableListOf<Correction>()

        val origWords = original.split(" ").toList()
        val corrWords = corrected.split(" ").toList()

        val patch = DiffUtils.diff(origWords, corrWords)

        for (delta in patch.deltas) {
            when (delta.type) {
                DeltaType.CHANGE -> {
                    val origText = delta.source.lines.joinToString(" ")
                    val corrText = delta.target.lines.joinToString(" ")
                    val position = findPhrasePosition(original, origText)

                    if (position != -1) {
                        corrections.add(
                            Correction(
                                original = origText,
                                startIndex = position,
                                endIndex = position + origText.length,
                                suggestions = listOf(corrText),
                                type = CorrectionType.SPELLING
                            )
                        )
                    }
                }

                DeltaType.DELETE -> {
                    val origText = delta.source.lines.joinToString(" ")
                    val position = findPhrasePosition(original, origText)

                    if (position != -1) {
                        corrections.add(
                            Correction(
                                original = origText,
                                startIndex = position,
                                endIndex = position + origText.length,
                                suggestions = emptyList(),
                                type = CorrectionType.SPELLING
                            )
                        )
                    }
                }

                else -> {
                    // INSERT delta type - we can ignore as we don't have original text to map to
                }
            }
        }

        return corrections
    }

    private fun findPhrasePosition(text: String, phrase: String): Int {
        return text.indexOf(phrase)
    }
}