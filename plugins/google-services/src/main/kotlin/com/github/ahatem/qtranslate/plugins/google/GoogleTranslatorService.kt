package com.github.ahatem.qtranslate.plugins.google


import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.HttpClient
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.PluginJson
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.ahatem.qtranslate.plugins.common.getOnce
import com.github.ahatem.qtranslate.plugins.common.sendJson
import com.github.ahatem.qtranslate.plugins.google.common.GoogleLanguageMapper
import com.github.ahatem.qtranslate.plugins.google.common.OfficialTranslateResponse
import com.github.ahatem.qtranslate.plugins.google.common.TranslateResponse
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.fold
import com.github.michaelbull.result.toResultOr
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class GoogleTranslatorService(
    private val pluginContext: PluginContext,
    private val settings: GoogleSettings,
    private val httpClient: HttpClient,
    private val languageMapper: GoogleLanguageMapper,
    private val apiConfig: ApiConfig,
    private val endpointHealth: GoogleEndpointHealth
) : Translator {


    override val key: String = "google-translator"
    override val name: String = "Google Translate"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/google-translate-icon.svg"

    private val officialParser = createJsonParser<OfficialTranslateResponse>(pluginContext)
    private val translateParser = createJsonParser<TranslateResponse>(pluginContext)

    override val supportedLanguages: SupportedLanguages = SupportedLanguages.Dynamic

    companion object {
        private const val TRANSLATE_PRIMARY = "https://translate.googleapis.com/translate_a/single"
        private const val TRANSLATE_FALLBACK = "https://clients5.google.com/translate_a/t"
        private const val TRANSLATE_OFFICIAL = "https://translation.googleapis.com/language/translate/v2"
        private val TRANSLATE_FEATURES = listOf("t", "bd", "at", "ex", "ld", "md", "rw", "rm", "ss", "qc")
    }

    override suspend fun fetchSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        languageMapper.getSupportedLanguages()

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> {
        if (settings.translateApiKey.isNotBlank()) {
            pluginContext.logger.info("Using official Google Translate API")
            val officialResult = translateWithOfficialAPI(request)
            if (officialResult.isOk) return officialResult
            pluginContext.logger.info("Official API failed, falling back to unofficial endpoint")
        }
        return translateWithUnofficialAPI(request)
    }

    private suspend fun translateWithOfficialAPI(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val sourceTag = languageMapper.toProviderCode(request.sourceLanguage)
        val targetTag = languageMapper.toProviderCode(request.targetLanguage)

        val requestBody = mapOf(
            "q" to request.text,
            "source" to sourceTag,
            "target" to targetTag,
            "format" to "text"
        )

        val responseString = httpClient.sendJson(
            url = TRANSLATE_OFFICIAL,
            headers = apiConfig.createJsonHeaders(),
            body = requestBody,
            queryParams = mapOf("key" to settings.translateApiKey)
        ).bind()

        val parsed = officialParser.parse(responseString).bind()
        val firstTranslation = parsed.data.translations.firstOrNull()
            .toResultOr { ServiceError.InvalidResponseError("No translation in response", null) }
            .bind()

        TranslationResponse(
            translatedText = firstTranslation.translatedText,
            detectedLanguage = firstTranslation.detectedSourceLanguage?.let {
                languageMapper.fromProviderCode(it)
            }
        )
    }

    private suspend fun translateWithUnofficialAPI(
        request: TranslationRequest
    ): Result<TranslationResponse, ServiceError> {
        val sourceTag = languageMapper.toProviderCode(request.sourceLanguage)
        val targetTag = languageMapper.toProviderCode(request.targetLanguage)

        val permit = endpointHealth.tryAcquirePrimary()
        if (permit == null) {
            pluginContext.logger.info("Primary endpoint unhealthy, using fallback")
        } else {
            // The permit is released on every path exactly once, including cancellation and any
            // error escaping the primary attempt, so an abandoned probe never strands the circuit.
            try {
                val primaryResult = tryPrimaryEndpoint(request.text, sourceTag, targetTag)
                primaryResult.fold(
                    success = {
                        currentCoroutineContext().ensureActive()
                        endpointHealth.recordSuccess(permit)
                        return primaryResult
                    },
                    failure = { error ->
                        currentCoroutineContext().ensureActive()
                        if (error.suppressesFallback()) return Err(error)
                        if (error.isRetryable) endpointHealth.recordTransientFailure(permit, error)
                    }
                )
                pluginContext.logger.info("Primary endpoint failed, trying fallback")
            } finally {
                endpointHealth.releasePrimary(permit)
            }
        }

        currentCoroutineContext().ensureActive()
        return tryFallbackEndpoint(request.text, sourceTag, targetTag)
    }

    /**
     * Whether a primary failure is about the request itself rather than the endpoint's reachability.
     * The request would fail identically against the fallback, so these errors are returned as-is
     * and leave the circuit untouched. Every other error, including a 200 response whose body the
     * parser cannot decode, still falls back, because the secondary host speaks a different protocol
     * and may well serve it.
     */
    private fun ServiceError.suppressesFallback(): Boolean = when (this) {
        is ServiceError.AuthenticationError,
        is ServiceError.InvalidInputError,
        is ServiceError.ValidationError,
        is ServiceError.UnsupportedLanguageError,
        is ServiceError.ConfigurationError -> true
        else -> false
    }

    private suspend fun tryPrimaryEndpoint(
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val responseString = httpClient.getOnce(
            url = TRANSLATE_PRIMARY,
            headers = apiConfig.createHeaders(),
            queryParams = mapOf(
                "client" to "gtx",
                "ie" to "UTF-8",
                "oe" to "UTF-8",
                "dj" to 1,
                "dt" to TRANSLATE_FEATURES,
                "sl" to sourceTag,
                "tl" to targetTag,
                "q" to text
            )
        ).bind()

        val parsed = translateParser.parse(responseString).bind()
        val translatedText = parsed.sentences.joinToString("") { it.text.orEmpty() }
        val detectedLang = languageMapper.fromProviderCode(parsed.sourceLanguage)
        val alternatives = parsed.dictionary?.firstOrNull()?.terms?.take(3) ?: emptyList()

        TranslationResponse(
            translatedText = translatedText.trim(),
            detectedLanguage = detectedLang,
            alternatives = alternatives
        )
    }

    private suspend fun tryFallbackEndpoint(
        text: String,
        sourceTag: String,
        targetTag: String
    ): Result<TranslationResponse, ServiceError> = coroutineBinding {
        val responseString = httpClient.get(
            url = TRANSLATE_FALLBACK,
            headers = apiConfig.createHeaders(),
            queryParams = mapOf(
                "client" to "dict-chrome-ex",
                "dj" to 1,
                "sl" to sourceTag,
                "tl" to targetTag,
                "q" to text
            )
        ).bind()

        parseFallbackResponse(responseString).bind()
    }

    /**
     * Parses the fallback endpoint's response, which is not documented and has been observed in two
     * array shapes. The payload is inspected field by field rather than decoded into a fixed type, so
     * an unexpected shape fails normally instead of throwing while indexing.
     */
    private fun parseFallbackResponse(
        responseString: String
    ): Result<TranslationResponse, ServiceError> {
        val root = runCatching { PluginJson.parseToJsonElement(responseString) }
            .getOrElse { return Err(ServiceError.InvalidResponseError("Fallback response is not valid JSON", it)) }

        val fields = fallbackFields(root)
            ?: return Err(ServiceError.InvalidResponseError("Unsupported fallback response shape", null))

        val translatedText = fields.firstOrNull()?.stringOrNull()?.trim()
        if (translatedText.isNullOrEmpty()) {
            return Err(ServiceError.InvalidResponseError("No translation in fallback response", null))
        }

        val detectedLanguage = fields.getOrNull(1)
            ?.stringOrNull()
            ?.let { languageMapper.fromProviderCode(it) }

        return Ok(TranslationResponse(translatedText = translatedText, detectedLanguage = detectedLanguage))
    }

    /**
     * Extracts the flat field list from the two known fallback shapes: a nested
     * `[[translated, src, ...]]` or a flat `[translated, src, ...]`. Any other shape yields null.
     */
    private fun fallbackFields(root: JsonElement): List<JsonElement>? = when (root) {
        is JsonArray -> when (val first = root.firstOrNull()) {
            is JsonArray -> first.toList()
            is JsonPrimitive -> root.toList()
            else -> null
        }
        else -> null
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

}