package com.github.ahatem.qtranslate.plugins.bing

import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.PluginContext
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.plugins.common.ApiConfig
import com.github.ahatem.qtranslate.plugins.common.KtorHttpClient
import com.github.ahatem.qtranslate.plugins.common.createJsonParser
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.toResultOr

class BingTranslatorService(
    private val pluginContext: PluginContext,
    private val httpClient: KtorHttpClient,
    private val authManager: BingAuthManager,
    private val languageMapper: BingLanguageMapper,
    private val apiConfig: ApiConfig
) : Translator {

    override val id: String = "bing-translator"
    override val name: String = "Bing Translate"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/bing-translate-icon.svg"

    private val parser = createJsonParser<List<BingTranslateResponse>>(pluginContext)

    companion object {
        private const val TRANSLATE_URL = "https://www.bing.com/ttranslatev3"
    }

    override suspend fun getSupportedLanguages(): Result<Set<LanguageCode>, ServiceError> =
        languageMapper.getSupportedLanguages()

    override suspend fun translate(request: TranslationRequest): Result<TranslationResponse, ServiceError> =
        coroutineBinding {
            val auth = authManager.getAuth().bind()
            val fromLang = languageMapper.toProviderCode(request.sourceLanguage)
            val toLang = languageMapper.toProviderCode(request.targetLanguage)

            val formData = buildFormData(request.text, fromLang, toLang, auth)
            val headers = buildHeaders(apiConfig)
            val queryParams = buildQueryParams(auth)

            val responseString = httpClient.postForm(
                url = TRANSLATE_URL,
                formData = formData,
                headers = headers,
                queryParams = queryParams,
                cookies = mapOf("MUID" to auth.muid)
            ).bind()

            val responses = parser.parse(responseString).bind()
            val response = responses.firstOrNull {
                it.detectedLanguage != null && it.translations != null
            }.toResultOr {
                ServiceError.InvalidResponseError("Empty or invalid response from Bing", null)
            }.bind()

            TranslationResponse(
                translatedText = response.translations!!.joinToString("") { it.text },
                detectedLanguage = languageMapper.fromProviderCode(response.detectedLanguage!!.language)
            )
        }

    private fun buildFormData(text: String, from: String, to: String, auth: BingAuth): Map<String, String> =
        mapOf(
            "text" to text,
            "fromLang" to from,
            "to" to to,
            "token" to auth.token,
            "key" to auth.key,
            "isAuthv2" to "true",
            "tryFetchingGenderDebiasedTranslations" to "true"
//            "tone" // [Casual, Formal, Standard] Standard will replace tone with tryFetchingGenderDebiasedTranslations: true
        )

    private fun buildHeaders(apiConfig: ApiConfig): Map<String, String> = apiConfig.createHeaders()

    private fun buildQueryParams(auth: BingAuth): Map<String, Any> = mapOf(
        "isVertical" to 1,
        "IG" to auth.ig,
        "IID" to auth.iid
    )
}